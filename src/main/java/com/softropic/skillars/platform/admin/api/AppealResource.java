package com.softropic.skillars.platform.admin.api;

import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.admin.contract.AppealDto;
import com.softropic.skillars.platform.admin.contract.SubmitAppealRequest;
import com.softropic.skillars.platform.admin.service.AppealService;
import com.softropic.skillars.platform.security.service.SecurityUtil;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User side of FR-ADM-004 appeals. Any authenticated user may call these; whether the caller may
 * appeal a given record (they are its subject, it is still enforced, the 14-day window is open, no
 * earlier appeal exists) is decided by {@link AppealService#submit}.
 */
@RestController
@RequestMapping("/api/appeals")
@RequiredArgsConstructor
@Observed(name = "appeals")
public class AppealResource {

    private final AppealService appealService;
    private final SecurityUtil securityUtil;

    @PostMapping
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "appeals.submit")
    public ResponseEntity<AppealDto> submit(@Valid @RequestBody SubmitAppealRequest request) {
        AppealDto appeal = appealService.submit(
            securityUtil.requireCurrentUserId(), request.subjectType(), request.subjectId(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(appeal);
    }

    @GetMapping("/me")
    @PreAuthorize(SecurityConstants.IS_AUTHENTICATED)
    @Observed(name = "appeals.listMine")
    public ResponseEntity<List<AppealDto>> listMine() {
        return ResponseEntity.ok(appealService.listMine(securityUtil.requireCurrentUserId()));
    }
}
