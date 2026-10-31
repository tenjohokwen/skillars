package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@Observed(name = "security.sanitize_preview")
@RestController
@RequestMapping("/api/util")
@RequiredArgsConstructor
public class SanitizePreviewResource {

    /**
     * S-04: the endpoint answers "was this text altered?", which is exactly the oracle needed to tune a
     * bypass. It stays (the profile builder and identity dialog use it for the live contact-details
     * warning, debounced at 400 ms), but is capped per authenticated user — generous for a human typing,
     * far too slow for probing — and the save paths now enforce the same rules server-side, so the
     * preview is a convenience, not the control.
     */
    static final int PREVIEWS_PER_USER_PER_MINUTE = 60;

    private final ContactDetailSanitizer contactDetailSanitizer;
    private final RateLimitingService rateLimitingService;

    @Value("${rate.limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    public record SanitizePreviewRequest(@NotNull @Size(max = 2000) String text) {}

    public record SanitizePreviewResponse(String original, String sanitized, boolean detectionFound) {}

    @PostMapping("/sanitize-preview")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public ResponseEntity<SanitizePreviewResponse> preview(@RequestBody @Valid SanitizePreviewRequest request,
                                                           Authentication authentication) {
        if (rateLimitingEnabled && !rateLimitingService.tryConsume(authentication.getName(),
                "util.sanitize_preview", PREVIEWS_PER_USER_PER_MINUTE, 1, TimeUnit.MINUTES)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        ContactDetailSanitizer.SanitizerResult result = contactDetailSanitizer.sanitize(request.text());
        return ResponseEntity.ok(new SanitizePreviewResponse(request.text(), result.sanitized(), result.wasModified()));
    }
}
