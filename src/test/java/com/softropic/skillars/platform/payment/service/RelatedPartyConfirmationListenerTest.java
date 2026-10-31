package com.softropic.skillars.platform.payment.service;

import com.softropic.skillars.platform.booking.contract.BookingConfirmedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RelatedPartyConfirmationListenerTest {

    @Mock RelatedPartyService relatedPartyService;
    @InjectMocks RelatedPartyConfirmationListener listener;

    private BookingConfirmedEvent event(UUID bookingId) {
        return BookingConfirmedEvent.builder().source(this).bookingId(bookingId).parentId(1L).build();
    }

    @Test
    void confirmedBooking_isEvaluatedWithTheConfirmationTrigger() {
        UUID id = UUID.randomUUID();
        listener.onBookingConfirmed(event(id));
        verify(relatedPartyService).evaluate(id, RelatedPartyService.TRIGGER_CONFIRMATION);
    }

    @Test
    void evaluationFailure_isSwallowed_soAPaidBookingIsNeverRolledBack() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("boom")).when(relatedPartyService).evaluate(id, "confirmation");
        assertThatCode(() -> listener.onBookingConfirmed(event(id))).doesNotThrowAnyException();
    }
}
