package com.softropic.skillars.platform.filestorage.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.ConfirmUploadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignDownloadResponse;
import com.softropic.skillars.platform.filestorage.contract.SignUploadRequest;
import com.softropic.skillars.platform.filestorage.contract.SignUploadResponse;
import com.softropic.skillars.platform.filestorage.service.FileStorageService;
import com.softropic.skillars.platform.security.contract.Principal;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

@Slf4j
@Observed(name = "storage")
@RestController
@RequestMapping("/api/storage")
@RequiredArgsConstructor
public class StorageResource {

    private final FileStorageService fileStorageService;
    private final SecurityUtil securityUtil;

    // Owner-bound entities: entityId must match the authenticated user's own ID
    private static final java.util.Set<String> OWNER_BOUND_ENTITIES = java.util.Set.of("coach_profile");

    @PostMapping("/sign/upload")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    @Observed(name = "storage.sign.upload")
    public ResponseEntity<SignUploadResponse> signUpload(
        @RequestBody @Valid SignUploadRequest request) {
        if (OWNER_BOUND_ENTITIES.contains(request.entity())) {
            String ownerId = ((Principal) securityUtil.getCurrentUser()).getBusinessId();
            if (!ownerId.equals(request.entityId())) {
                throw new AccessDeniedException("entityId must match authenticated user");
            }
        }
        return ResponseEntity.ok(fileStorageService.signUpload(request));
    }

    @PostMapping("/confirm/**")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    @Observed(name = "storage.confirm.upload")
    public ResponseEntity<ConfirmUploadResponse> confirmUpload(
        HttpServletRequest request,
        @RequestBody @Valid ConfirmUploadRequest body) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String key = fullPath.substring("/api/storage/confirm/".length());
        return ResponseEntity.ok(fileStorageService.confirmUpload(key, body));
    }

    @GetMapping("/sign/download/**")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    @Observed(name = "storage.sign.download")
    public ResponseEntity<SignDownloadResponse> signDownload(HttpServletRequest request) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String key = fullPath.substring("/api/storage/sign/download/".length());
        return ResponseEntity.ok(fileStorageService.signDownload(key));
    }

    @DeleteMapping("/**")
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    @Observed(name = "storage.delete")
    public ResponseEntity<Void> deleteFile(HttpServletRequest request) {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String key = fullPath.substring("/api/storage/".length());
        fileStorageService.softDelete(key, securityUtil.getCurrentUserName());
        return ResponseEntity.noContent().build();
    }
}
