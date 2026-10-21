package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.PlayerRequestResponse;
import com.softropic.skillars.platform.booking.contract.UpdatePlayerRequestRequest;
import com.softropic.skillars.platform.booking.service.BookingService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * skillars-deferred-155 AC5: the player/parent-initiated session-prep request ("work on X next
 * time"). Deliberately its own resource, not folded into {@code BookingResource}
 * ({@code @RequestMapping("/api/bookings/requests")}, scoped to pre-confirmation request
 * creation/listing/accept/decline) — this is a post-confirmation mutation, matching
 * {@link CancellationResource}/{@link RescheduleResource}/{@link SessionCompletionResource}'s own
 * one-small-resource-per-concern pattern under {@code /api/bookings}.
 */
@Observed(name = "booking.playerRequest")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class PlayerRequestResource {

    private final BookingService bookingService;
    private final SecurityUtil securityUtil;

    @PatchMapping("/{id}/player-request")
    @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
    public ResponseEntity<PlayerRequestResponse> setPlayerRequest(
            @PathVariable UUID id, @RequestBody @Valid UpdatePlayerRequestRequest req) {
        return ResponseEntity.ok(bookingService.setPlayerRequest(id, currentCallerId(), req.playerRequest()));
    }

    private Long currentCallerId() {
        return securityUtil.requireCurrentUserId();
    }
}
