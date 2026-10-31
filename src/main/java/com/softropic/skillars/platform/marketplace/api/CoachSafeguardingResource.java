package com.softropic.skillars.platform.marketplace.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.marketplace.contract.CoachSafeguardingStatusResponse;
import com.softropic.skillars.platform.marketplace.contract.MarketplaceException;
import com.softropic.skillars.platform.marketplace.service.CoachSafeguardingService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * S-09: the coach's side of the optional safeguarding check - see its status, ask Skillars staff to inspect their
 * certificate (optionally attaching it) and withdraw. The coach can never set the badge; staff do, through
 * {@code AdminSafeguardingResource}. The upload is capped at 5 MB by {@code spring.servlet.multipart}.
 */
@Observed(name = "marketplace.safeguarding")
@RestController
@RequestMapping("/api/marketplace/coaches/me/safeguarding")
@RequiredArgsConstructor
public class CoachSafeguardingResource {

    private final CoachSafeguardingService safeguardingService;
    private final SecurityUtil securityUtil;

    @GetMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachSafeguardingStatusResponse> getStatus() {
        return ResponseEntity.ok(safeguardingService.getOwnStatus(securityUtil.requireCurrentUserId()));
    }

    /** Multipart with an optional {@code file} part: no file means "inspect it another way" (e.g. a video call). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<CoachSafeguardingStatusResponse> request(
            @RequestPart(value = "file", required = false) MultipartFile file) {
        byte[] bytes = null;
        if (file != null && !file.isEmpty()) {
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new MarketplaceException("marketplace.safeguardingFileEmpty", "Could not read the uploaded file");
            }
        }
        return ResponseEntity.ok(safeguardingService.requestVerification(securityUtil.requireCurrentUserId(), bytes));
    }

    @DeleteMapping
    @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
    public ResponseEntity<Void> withdraw() {
        safeguardingService.withdraw(securityUtil.requireCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
