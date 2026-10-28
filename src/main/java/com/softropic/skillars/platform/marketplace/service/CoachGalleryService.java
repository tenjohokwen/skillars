package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
import com.softropic.skillars.platform.filestorage.contract.StorageObjectDto;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.marketplace.contract.CoachMediaItemDto;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItem;
import com.softropic.skillars.platform.marketplace.repo.CoachMediaItemRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfile;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * feat/coach-workflow-gaps D9: a coach's own public-profile media gallery (story 2.3: "up to 6 images or short
 * clips"). Before this, {@code coach_media} was read by the public profile but nothing could write it.
 *
 * <p>Images only. Files go through the {@code filestorage} module exactly like the profile photo (sign → PUT →
 * confirm, entity {@code coach_gallery} keyed by the coach's own user id — owner-bound in
 * {@code StorageResource}); this service then links a confirmed key after re-validating it server-side against
 * the HEAD-verified storage record (owner, content type, size), never the client's declaration. Short video
 * clips are not supported here: video goes through the {@code video} module's Bunny pipeline, not object
 * storage, and is left as a follow-up.
 *
 * <p>Race-safety: the max-count check and the insert run while this transaction holds the coach profile row
 * {@code FOR UPDATE} (the same lock {@code CoachProfileService.deletePhoto}/{@code saveStep4} take), so two
 * concurrent uploads cannot both observe 5 items and land a 7th.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachGalleryService {

    public static final int MAX_ITEMS = 6;
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final String STORAGE_ENTITY = "coach_gallery";

    private final CoachProfileRepository coachProfileRepository;
    private final CoachMediaItemRepository coachMediaItemRepository;
    private final FileStorageService fileStorageService;
    private final PessimisticLockRetryer lockRetryer;

    @Transactional(readOnly = true)
    public List<CoachMediaItemDto> listOwn(Long userId) {
        CoachProfile profile = requireProfile(userId);
        return coachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc(profile.getId()).stream()
            .map(this::toDto)
            .toList();
    }

    @Transactional
    public CoachMediaItemDto addImage(Long userId, String storageKey) {
        CoachProfile profile = requireProfile(userId);

        if (storageKey == null || !storageKey.startsWith(STORAGE_ENTITY + "/" + userId + "/")) {
            throw new MarketplaceException("marketplace.invalidGalleryKey", "Invalid gallery key");
        }
        StorageObjectDto object = fileStorageService.findActiveObject(storageKey)
            .orElseThrow(() -> new MarketplaceException("marketplace.invalidGalleryKey", "Upload not confirmed"));
        if (!String.valueOf(userId).equals(object.ownerId())) {
            throw new MarketplaceException("marketplace.invalidGalleryKey", "File does not belong to caller");
        }
        if (object.contentType() == null || !ALLOWED_CONTENT_TYPES.contains(object.contentType())) {
            throw new MarketplaceException("marketplace.galleryInvalidType", "Only JPEG, PNG or WebP images");
        }
        if (object.sizeBytes() > MAX_IMAGE_BYTES) {
            throw new MarketplaceException("marketplace.galleryFileTooLarge", "Image exceeds 5 MB");
        }

        lockProfile(profile.getId());

        List<CoachMediaItem> existing = coachMediaItemRepository.findByCoachIdOrderByDisplayOrderAsc(profile.getId());
        // Idempotent re-submit of the same key (double click / retried request).
        for (CoachMediaItem item : existing) {
            if (storageKey.equals(item.getFileUrl())) {
                return toDto(item);
            }
        }
        if (existing.size() >= MAX_ITEMS) {
            throw new MarketplaceException("marketplace.galleryFull", "Gallery already holds " + MAX_ITEMS + " items");
        }
        int nextOrder = existing.stream().mapToInt(CoachMediaItem::getDisplayOrder).max().orElse(-1) + 1;

        CoachMediaItem item = new CoachMediaItem();
        item.setCoachId(profile.getId());
        item.setFileUrl(storageKey);
        item.setMediaType("IMAGE");
        item.setDisplayOrder(nextOrder);
        return toDto(coachMediaItemRepository.save(item));
    }

    /**
     * Removes one gallery item. The storage object is soft-deleted with the same tolerance as
     * {@code CoachProfileService.deletePhoto} (already-gone or stale-{@code created_by} objects still let the
     * coach remove the row). Unknown or another coach's item: 404, never confirming it exists.
     */
    @Transactional
    public void deleteItem(Long userId, UUID mediaId, String currentUserLogin) {
        CoachProfile profile = requireProfile(userId);
        lockProfile(profile.getId());

        CoachMediaItem item = coachMediaItemRepository.findById(mediaId)
            .filter(m -> profile.getId().equals(m.getCoachId()))
            .orElseThrow(() -> new ResourceNotFoundException("Gallery item not found", "coach_media"));

        if (isStorageKey(item.getFileUrl())) {
            try {
                fileStorageService.softDelete(item.getFileUrl(), currentUserLogin);
            } catch (StorageObjectNotFoundException e) {
                log.warn("deleteGalleryItem: storage object for coachId={} already gone", profile.getId());
            } catch (AuthorizationException e) {
                log.warn("deleteGalleryItem: storage object for coachId={} has a stale created_by", profile.getId());
            }
        }
        coachMediaItemRepository.delete(item);
    }

    private void lockProfile(UUID coachId) {
        lockRetryer.withBoundedRetry("CoachGalleryService.lockProfile", () ->
            coachProfileRepository.findByIdForUpdate(coachId)
                .orElseThrow(() -> new MarketplaceException("marketplace.profileNotFound", "Coach profile not found")));
    }

    private CoachProfile requireProfile(Long userId) {
        return coachProfileRepository.findByUserId(userId)
            .orElseThrow(() -> new MarketplaceException("marketplace.profileNotFound",
                "Coach profile not found for userId=" + userId));
    }

    CoachMediaItemDto toDto(CoachMediaItem m) {
        return new CoachMediaItemDto(m.getId(), resolveUrl(m.getFileUrl()), m.getMediaType(), m.getDisplayOrder());
    }

    /**
     * Items uploaded here store the private-bucket object key, which a browser cannot load; legacy/seeded
     * rows may hold an absolute URL. Resolve keys to a signed download URL, pass absolute URLs through.
     */
    public String resolveUrl(String fileUrl) {
        return resolveUrl(fileStorageService, fileUrl);
    }

    /** The one gallery URL rule, shared with the public profile ({@code CoachProfileService}). */
    public static String resolveUrl(FileStorageService storage, String fileUrl) {
        return isStorageKey(fileUrl) ? storage.signedDownloadUrl(fileUrl) : fileUrl;
    }

    public static boolean isStorageKey(String fileUrl) {
        return fileUrl != null && fileUrl.startsWith(STORAGE_ENTITY + "/");
    }
}
