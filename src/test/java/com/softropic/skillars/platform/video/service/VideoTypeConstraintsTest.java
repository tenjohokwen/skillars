package com.softropic.skillars.platform.video.service;

import com.softropic.skillars.platform.config.service.ConfigService;
import com.softropic.skillars.platform.video.contract.VideoType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * skillars-deferred-107 AC2 + code review: {@code video.<type>.maxSizeBytes} /
 * {@code maxDurationSeconds} are read through the range-bounded accessor — a stored 0 would
 * otherwise reject every upload of that type. Revert a call site to {@code getLong(key)} and the
 * matching {@code verify(...)} here fails.
 */
@ExtendWith(MockitoExtension.class)
class VideoTypeConstraintsTest {

    @Mock ConfigService configService;

    @InjectMocks VideoTypeConstraints videoTypeConstraints;

    @Test
    void maxSizeBytes_homework_readsThroughBoundedAccessor() {
        when(configService.getBoundedLong("video.homework.maxSizeBytes", 1L, Long.MAX_VALUE))
            .thenReturn(262_144_000L);

        assertThat(videoTypeConstraints.getMaxSizeBytes(VideoType.HOMEWORK)).isEqualTo(262_144_000L);
        verify(configService).getBoundedLong("video.homework.maxSizeBytes", 1L, Long.MAX_VALUE);
    }

    @Test
    void maxDurationSeconds_drillDemo_readsThroughBoundedAccessor_withDayCeiling() {
        when(configService.getBoundedLong("video.drillDemo.maxDurationSeconds", 1L, 86400L))
            .thenReturn(120L);

        assertThat(videoTypeConstraints.getMaxDurationSeconds(VideoType.DRILL_DEMO)).isEqualTo(120);
        verify(configService).getBoundedLong("video.drillDemo.maxDurationSeconds", 1L, 86400L);
    }

    @Test
    void maxSizeBytes_coachReview_usesCamelCaseSegment() {
        when(configService.getBoundedLong("video.coachReview.maxSizeBytes", 1L, Long.MAX_VALUE))
            .thenReturn(1_073_741_824L);

        assertThat(videoTypeConstraints.getMaxSizeBytes(VideoType.COACH_REVIEW)).isEqualTo(1_073_741_824L);
        verify(configService).getBoundedLong("video.coachReview.maxSizeBytes", 1L, Long.MAX_VALUE);
    }
}
