package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.sanitizer.ContactDetailSanitizer;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Observed(name = "security.sanitize_preview")
@RestController
@RequestMapping("/api/util")
@RequiredArgsConstructor
public class SanitizePreviewResource {

    private final ContactDetailSanitizer contactDetailSanitizer;

    public record SanitizePreviewRequest(@NotNull String text) {}

    public record SanitizePreviewResponse(String sanitized, boolean wasModified) {}

    @PostMapping("/sanitize-preview")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public ResponseEntity<SanitizePreviewResponse> preview(@RequestBody @Valid SanitizePreviewRequest request) {
        ContactDetailSanitizer.SanitizerResult result = contactDetailSanitizer.sanitize(request.text());
        return ResponseEntity.ok(new SanitizePreviewResponse(result.sanitized(), result.wasModified()));
    }
}
