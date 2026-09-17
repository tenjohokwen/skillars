package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoRepositoryIT extends BaseVideoIT {

    private static final String OWNER_ID = "owner-9310-repo-it";

    @Autowired VideoRepository videoRepository;


    @Test
    void duplicateProviderAssetId_throwsDataIntegrity() {
        videoRepository.saveAndFlush(seedVideo("asset-9310-dup"));

        assertThatThrownBy(() -> videoRepository.saveAndFlush(seedVideo("asset-9310-dup")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullProviderAssetId_multipleVideosCoexist() {
        videoRepository.saveAndFlush(seedVideo(null));

        assertThatCode(() -> videoRepository.saveAndFlush(seedVideo(null)))
            .doesNotThrowAnyException();
    }

    // skillars-deferred-117 AC2: findArchivedExceedingThreshold must not permanently re-select a
    // video markPurged() already purged. No existing test in this file covered this query at all —
    // this is new coverage, not an extension.
    @Test
    void findArchivedExceedingThreshold_returnsReadyArchivedVideo_excludesAlreadyPurgedVideo() {
        Instant threshold = Instant.now().minus(90, ChronoUnit.DAYS);
        Instant pastThreshold = threshold.minus(1, ChronoUnit.DAYS);

        Video dueForDeletion = seedArchivedVideo("asset-9311-due", OperationalState.READY, pastThreshold);
        Video alreadyPurged = seedArchivedVideo("asset-9311-purged", OperationalState.DELETED, pastThreshold);
        videoRepository.saveAndFlush(dueForDeletion);
        videoRepository.saveAndFlush(alreadyPurged);

        List<Video> candidates = videoRepository.findArchivedExceedingThreshold(threshold, 100);
        List<UUID> candidateIds = candidates.stream().map(Video::getId).toList();

        assertThat(candidateIds).contains(dueForDeletion.getId());
        assertThat(candidateIds).doesNotContain(alreadyPurged.getId());
    }

    private Video seedArchivedVideo(String providerAssetId, OperationalState operationalState, Instant archivedAt) {
        Video v = seedVideo(providerAssetId);
        v.setOperationalState(operationalState);
        v.setAccessState(AccessState.ARCHIVED);
        v.setArchivedAt(archivedAt);
        return v;
    }

    private Video seedVideo(String providerAssetId) {
        Video v = new Video();
        v.setOwnerId(OWNER_ID);
        v.setProvider("bunny");
        v.setProviderAssetId(providerAssetId);
        v.setTitle("test-9310.mp4");
        v.setOperationalState(OperationalState.READY);
        v.setAccessState(AccessState.ACTIVE);
        v.setVisibility(Visibility.PRIVATE);
        return v;
    }
}
