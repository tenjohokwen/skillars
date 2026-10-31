package com.softropic.skillars.platform.filestorage.service;

import com.softropic.skillars.infrastructure.feature.AppFeature;
import com.softropic.skillars.infrastructure.feature.FeatureToggleService;
import com.softropic.skillars.platform.filestorage.repo.ScanStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * S-18: which uploads need an abuse-media (Project Arachnid) verdict before anyone but the uploader may see them.
 * Images only: those are the uploads that become visible to other people (profile photo, gallery, branding logo).
 * With scanning switched off (an explicit pre-launch override in a deployed environment, or dev) nothing is held.
 */
@Component
@RequiredArgsConstructor
public class MediaScanPolicy {

    private final FeatureToggleService featureToggleService;

    public boolean scanningEnabled() {
        return featureToggleService.isEnabled(AppFeature.ARACHNID_ENABLED);
    }

    /** The status a freshly confirmed object starts in. */
    public ScanStatus initialStatus(String contentType) {
        return scanningEnabled() && isScannable(contentType) ? ScanStatus.PENDING : ScanStatus.NOT_REQUIRED;
    }

    public static boolean isScannable(String contentType) {
        return contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/");
    }
}
