package com.softropic.skillars.platform.booking.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.softropic.skillars.infrastructure.config.CommonConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * skillars-12-6 AC4: {@code GdprExportService} hands the {@code Booking} entity straight to Jackson
 * for PARENT, PLAYER and COACH exports, so the coach's private plan note must be {@code @JsonIgnore}d.
 * {@code GdprExportServiceTest} is Mockito-only and never serialises anything, so this test serialises
 * through the application's own mapper configuration and then the same {@code copy().configure(...)}
 * step the export applies ({@code GdprExportService.buildExport}).
 *
 * <p>The injected {@code ObjectMapper} is Spring Boot's, built from {@link CommonConfig#objectMapperBuilder()}
 * (the only user-defined {@code Jackson2ObjectMapperBuilder}; it suppresses Boot's default builder and its
 * customizers, and no other {@code ObjectMapper} {@code @Bean} exists), so building from that bean method
 * reproduces the export's mapper without booting a context.
 */
class BookingJsonPrivacyTest {

    private static ObjectMapper exportMapper() {
        return new CommonConfig().objectMapperBuilder().build().copy()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    @Test
    void serialisedBooking_neverContainsCoachPlanNote_butStillContainsPlayerRequest() throws Exception {
        ObjectMapper mapper = exportMapper();
        Booking booking = new Booking();
        booking.setStatus("CONFIRMED");
        booking.setPlayerRequest("player-visible-request");
        booking.setCoachPlanNote("PRIVATE-COACH-PLAN-NOTE");
        booking.setRequestedStartTime(Instant.now());
        booking.setRequestedEndTime(Instant.now().plusSeconds(3600));

        String json = mapper.writeValueAsString(booking);

        assertThat(json).doesNotContain("PRIVATE-COACH-PLAN-NOTE").doesNotContain("coachPlanNote");
        assertThat(json).contains("player-visible-request");
    }

    @Test
    void isPrepNoteEditable_isNotSerialisedAsAProperty() throws Exception {
        ObjectMapper mapper = exportMapper();
        Booking booking = new Booking();
        booking.setStatus("CONFIRMED");

        assertThat(mapper.writeValueAsString(booking)).doesNotContain("prepNoteEditable");
    }
}
