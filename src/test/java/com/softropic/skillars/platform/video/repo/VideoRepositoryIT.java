package com.softropic.skillars.platform.video.repo;

import com.softropic.skillars.platform.video.BaseVideoIT;
import com.softropic.skillars.platform.video.contract.AccessState;
import com.softropic.skillars.platform.video.contract.OperationalState;
import com.softropic.skillars.platform.video.contract.Visibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoRepositoryIT extends BaseVideoIT {

    private static final String OWNER_ID = "owner-9310-repo-it";

    @Autowired VideoRepository videoRepository;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM main.videos WHERE owner_id = ?", OWNER_ID);
    }

    @Test
    void duplicateProviderAssetId_throwsDataIntegrity() {
        videoRepository.saveAndFlush(seedVideo("asset-9310-dup"));

        assertThatThrownBy(() -> videoRepository.saveAndFlush(seedVideo("asset-9310-dup")))
            .isInstanceOf(DataIntegrityViolationException.class);
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
