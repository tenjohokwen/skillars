package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.filestorage.contract.StorageObjectDto;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.contract.CoachMediaItemDto;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItem;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItemRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** feat/coach-workflow-gaps D9. */
@ExtendWith(MockitoExtension.class)
class CoachGalleryServiceTest {

    private static final Long USER_ID = 501L;
    private static final UUID COACH_ID = UUID.randomUUID();
    private static final String KEY = "coach_gallery/501/2026/10/abc.jpg";

    @Mock CoachProfileRepository coachProfileRepository;
    @Mock CoachMediaItemRepository coachMediaItemRepository;
    @Mock FileStorageService fileStorageService;
    @Mock PessimisticLockRetryer lockRetryer;
    @InjectMocks CoachGalleryService service;

    private CoachProfile profile;

    @BeforeEach
    void setUp() {
        profile = new CoachProfile();
        profile.setId(COACH_ID);
        lenient().when(coachProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(lockRetryer.withBoundedRetry(anyString(), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        lenient().when(coachProfileRepository.findByIdForUpdate(COACH_ID)).thenReturn(Optional.of(profile));
        lenient().when(fileStorageService.signedDownloadUrl(anyString())).thenAnswer(inv -> "https://signed/" + inv.getArgument(0));
    }

    private static StorageObjectDto object(String owner, String contentType, long size) {
        return new StorageObjectDto(KEY, owner, "abc.jpg", contentType, size, null, null, "s3", "b", Instant.now());
    }

    private static List<CoachMediaItem> items(int n) {
        List<CoachMediaItem> list = new ArrayList<>();
        IntStream.range(0, n).forEach(i -> {
            CoachMediaItem m = new CoachMediaItem();
            m.setId(UUID.randomUUID());
            m.setCoachId(COACH_ID);
            m.setFileUrl("coach_gallery/501/2026/10/" + i + ".jpg");
            m.setMediaType("IMAGE");
            m.setDisplayOrder(i);
            list.add(m);
        });
        return list;
    }

    @Test
    void addImage_valid_locksProfileBeforeCountingAndAppendsAtNextOrder() {
        when(fileStorageService.findActiveObject(KEY)).thenReturn(Optional.of(object("501", "image/jpeg", 1000)));
        when(coachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc(COACH_ID)).thenReturn(items(2));
        when(coachMediaItemRepository.save(any(CoachMediaItem.class))).thenAnswer(inv -> inv.getArgument(0));

        CoachMediaItemDto dto = service.addImage(USER_ID, KEY);

        assertThat(dto.displayOrder()).isEqualTo(2);
        assertThat(dto.mediaType()).isEqualTo("IMAGE");
        assertThat(dto.fileUrl()).isEqualTo("https://signed/" + KEY);
        InOrder order = inOrder(coachProfileRepository, coachMediaItemRepository);
        order.verify(coachProfileRepository).findByIdForUpdate(COACH_ID);
        order.verify(coachMediaItemRepository).findByCoachIdOrderByDisplayOrderAsc(COACH_ID);
        order.verify(coachMediaItemRepository).save(any());
    }

    @Test
    void addImage_galleryFull_rejected() {
        when(fileStorageService.findActiveObject(KEY)).thenReturn(Optional.of(object("501", "image/png", 1000)));
        when(coachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc(COACH_ID)).thenReturn(items(6));

        assertThatThrownBy(() -> service.addImage(USER_ID, KEY))
            .isInstanceOf(MarketplaceException.class)
            .extracting(e -> ((MarketplaceException) e).getErrorCode()).isEqualTo("marketplace.galleryFull");
        verify(coachMediaItemRepository, never()).save(any());
    }

    @Test
    void addImage_keyOfAnotherUser_rejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.addImage(USER_ID, "coach_gallery/999/2026/10/x.jpg"))
            .isInstanceOf(MarketplaceException.class);
        verify(fileStorageService, never()).findActiveObject(anyString());
    }

    @Test
    void addImage_storageOwnerMismatch_rejected() {
        when(fileStorageService.findActiveObject(KEY)).thenReturn(Optional.of(object("999", "image/jpeg", 10)));
        assertThatThrownBy(() -> service.addImage(USER_ID, KEY)).isInstanceOf(MarketplaceException.class);
    }

    @Test
    void addImage_notAnImage_rejected() {
        when(fileStorageService.findActiveObject(KEY)).thenReturn(Optional.of(object("501", "application/pdf", 10)));
        assertThatThrownBy(() -> service.addImage(USER_ID, KEY))
            .extracting(e -> ((MarketplaceException) e).getErrorCode()).isEqualTo("marketplace.galleryInvalidType");
    }

    @Test
    void addImage_tooLarge_rejected() {
        when(fileStorageService.findActiveObject(KEY))
            .thenReturn(Optional.of(object("501", "image/jpeg", CoachGalleryService.MAX_IMAGE_BYTES + 1)));
        assertThatThrownBy(() -> service.addImage(USER_ID, KEY))
            .extracting(e -> ((MarketplaceException) e).getErrorCode()).isEqualTo("marketplace.galleryFileTooLarge");
    }

    @Test
    void addImage_unconfirmedKey_rejected() {
        when(fileStorageService.findActiveObject(KEY)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addImage(USER_ID, KEY)).isInstanceOf(MarketplaceException.class);
    }

    @Test
    void addImage_sameKeyTwice_isIdempotent() {
        List<CoachMediaItem> existing = items(1);
        existing.get(0).setFileUrl(KEY);
        when(fileStorageService.findActiveObject(KEY)).thenReturn(Optional.of(object("501", "image/jpeg", 10)));
        when(coachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc(COACH_ID)).thenReturn(existing);

        CoachMediaItemDto dto = service.addImage(USER_ID, KEY);

        assertThat(dto.id()).isEqualTo(existing.get(0).getId());
        verify(coachMediaItemRepository, never()).save(any());
    }

    @Test
    void deleteItem_own_softDeletesFileAndRow() {
        CoachMediaItem item = items(1).get(0);
        when(coachMediaItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        service.deleteItem(USER_ID, item.getId(), "coach@x.com");

        verify(fileStorageService).softDelete(item.getFileUrl(), "coach@x.com");
        verify(coachMediaItemRepository).delete(item);
    }

    @Test
    void deleteItem_otherCoachsItem_404() {
        CoachMediaItem item = items(1).get(0);
        item.setCoachId(UUID.randomUUID());
        when(coachMediaItemRepository.findById(item.getId())).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.deleteItem(USER_ID, item.getId(), "coach@x.com"))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(coachMediaItemRepository, never()).delete(any());
    }

    @Test
    void resolveUrl_legacyAbsoluteUrl_passesThrough() {
        assertThat(service.resolveUrl("https://cdn.example/x.jpg")).isEqualTo("https://cdn.example/x.jpg");
    }
}
