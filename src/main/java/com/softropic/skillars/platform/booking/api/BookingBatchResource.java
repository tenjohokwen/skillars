package com.softropic.skillars.platform.booking.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.booking.contract.BatchBookingCreatedResponse;
import com.softropic.skillars.platform.booking.contract.BatchConfigResponse;
import com.softropic.skillars.platform.booking.contract.CreateBatchRequest;
import com.softropic.skillars.platform.booking.service.BookingBatchService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Observed(name = "booking.batch")
@RestController
@RequestMapping("/api/bookings/batches")
@RequiredArgsConstructor
public class BookingBatchResource {

    private final BookingBatchService batchService;
    private final SecurityUtil securityUtil;

    @GetMapping("/config")
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<BatchConfigResponse> getConfig() {
        return ResponseEntity.ok(new BatchConfigResponse(batchService.getMaxBatchSize()));
    }

    @PostMapping
    @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
    public ResponseEntity<BatchBookingCreatedResponse> createBatch(@Valid @RequestBody CreateBatchRequest req) {
        BatchBookingCreatedResponse response = batchService.createBatch(currentUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{batchId}/accept-all")
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> acceptAll(@PathVariable UUID batchId) {
        batchService.acceptAll(batchId, currentUserId());
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId() {
        return securityUtil.requireCurrentUserId();
    }
}
