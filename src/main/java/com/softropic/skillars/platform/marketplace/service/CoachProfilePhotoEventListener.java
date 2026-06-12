package com.softropic.skillars.platform.marketplace.service;

import com.softropic.skillars.platform.filestorage.event.StorageObjectConfirmedEvent;
import com.softropic.skillars.platform.filestorage.repo.FileStorageObject;
import com.softropic.skillars.platform.filestorage.service.StorageKeyGenerator;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoachProfilePhotoEventListener {

    private static final String COACH_PROFILE_ENTITY = "coach_profile";

    private final CoachProfileRepository coachProfileRepository;
    private final StorageKeyGenerator storageKeyGenerator;

    @EventListener
    @Transactional
    public void onStorageConfirmed(StorageObjectConfirmedEvent event) {
        FileStorageObject fso = event.getStorageObject();

        StorageKeyGenerator.StorageKeyParts keyParts = storageKeyGenerator.parse(fso.getKey())
            .filter(p -> COACH_PROFILE_ENTITY.equals(p.entity()))
            .orElse(null);

        if (keyParts == null) {
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(keyParts.entityId());
        } catch (NumberFormatException e) {
            log.warn("coach_profile storage event with non-numeric entityId in key: {}", fso.getKey());
            return;
        }

        coachProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setPhotoUrl(fso.getKey());
            coachProfileRepository.save(profile);
            log.info("Updated coach profile photo: userId={}", userId);
        });
    }
}
