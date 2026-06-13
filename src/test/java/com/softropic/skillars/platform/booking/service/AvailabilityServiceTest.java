package com.softropic.skillars.platform.booking.service;

import com.softropic.skillars.platform.booking.contract.AvailableSlotResponse;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlock;
import com.softropic.skillars.platform.booking.repo.CoachAvailabilityBlockRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachAvailabilityWindowRepository;
import com.softropic.skillars.platform.marketplace.repo.CoachProfileRepository;
import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock
    CoachAvailabilityWindowRepository windowRepository;

    @Mock
    CoachAvailabilityBlockRepository blockRepository;

    @Mock
    CoachProfileRepository coachProfileRepository;

    @InjectMocks
    AvailabilityService service;

    // ----- computeAvailableSlots tests -----

    @Test
    void computeAvailableSlots_noBlocks_returnsFullWindows() {
        Instant start = Instant.parse("2026-06-16T09:00:00Z");
        Instant end = Instant.parse("2026-06-16T13:00:00Z");

        List<AvailableSlotResponse> result = service.computeAvailableSlots(start, end, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).startDatetime()).isEqualTo(start);
        assertThat(result.get(0).endDatetime()).isEqualTo(end);
    }

    @Test
    void computeAvailableSlots_fullBlock_returnsEmpty() {
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T13:00:00Z");

        CoachAvailabilityBlock block = blockWith(
            Instant.parse("2026-06-16T08:00:00Z"),
            Instant.parse("2026-06-16T14:00:00Z")
        );

        List<AvailableSlotResponse> result = service.computeAvailableSlots(windowStart, windowEnd, List.of(block));

        assertThat(result).isEmpty();
    }

    @Test
    void computeAvailableSlots_partialOverlap_returnsTwoSegments() {
        // AC 6: 10:00–12:00 block on 09:00–13:00 window → segments [09:00–10:00] and [12:00–13:00]
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T13:00:00Z");

        CoachAvailabilityBlock block = blockWith(
            Instant.parse("2026-06-16T10:00:00Z"),
            Instant.parse("2026-06-16T12:00:00Z")
        );

        List<AvailableSlotResponse> result = service.computeAvailableSlots(windowStart, windowEnd, List.of(block));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startDatetime()).isEqualTo(Instant.parse("2026-06-16T09:00:00Z"));
        assertThat(result.get(0).endDatetime()).isEqualTo(Instant.parse("2026-06-16T10:00:00Z"));
        assertThat(result.get(1).startDatetime()).isEqualTo(Instant.parse("2026-06-16T12:00:00Z"));
        assertThat(result.get(1).endDatetime()).isEqualTo(Instant.parse("2026-06-16T13:00:00Z"));
    }

    @Test
    void computeAvailableSlots_multipleWindows_multipleBlocks() {
        // Window: 09:00–11:00, two blocks: 09:30–10:00 and 10:30–11:30
        // Expected: [09:00–09:30], [10:00–10:30] (11:00 cap applies)
        Instant windowStart = Instant.parse("2026-06-16T09:00:00Z");
        Instant windowEnd = Instant.parse("2026-06-16T11:00:00Z");

        CoachAvailabilityBlock block1 = blockWith(
            Instant.parse("2026-06-16T09:30:00Z"),
            Instant.parse("2026-06-16T10:00:00Z")
        );
        CoachAvailabilityBlock block2 = blockWith(
            Instant.parse("2026-06-16T10:30:00Z"),
            Instant.parse("2026-06-16T12:00:00Z")
        );

        List<AvailableSlotResponse> result = service.computeAvailableSlots(windowStart, windowEnd, List.of(block1, block2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).startDatetime()).isEqualTo(Instant.parse("2026-06-16T09:00:00Z"));
        assertThat(result.get(0).endDatetime()).isEqualTo(Instant.parse("2026-06-16T09:30:00Z"));
        assertThat(result.get(1).startDatetime()).isEqualTo(Instant.parse("2026-06-16T10:00:00Z"));
        assertThat(result.get(1).endDatetime()).isEqualTo(Instant.parse("2026-06-16T10:30:00Z"));
    }

    private CoachAvailabilityBlock blockWith(Instant start, Instant end) {
        return Instancio.of(CoachAvailabilityBlock.class)
            .set(field(CoachAvailabilityBlock::getId), UUID.randomUUID())
            .set(field(CoachAvailabilityBlock::getCoachId), UUID.randomUUID())
            .set(field(CoachAvailabilityBlock::getStartDatetime), start)
            .set(field(CoachAvailabilityBlock::getEndDatetime), end)
            .create();
    }
}
