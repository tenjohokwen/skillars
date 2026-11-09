package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.security.RateLimited;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.EmailChangeService;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two links of an email change (skillars-deferred-172): confirm, mailed to the new address, and undo, mailed
 * to the old one. Public (listed in {@code AppEndpoints.PUBLIC_ENDPOINTS}): the person clicking may not be signed
 * in, and after a confirm or undo nobody is. The single-use token is the credential; an unusable one answers
 * 400 {@code email.change.linkInvalid} whatever the cause. POST only, with the token in the body, so link
 * scanners that prefetch a GET cannot use it; the SPA page posts it after an explicit click.
 */
@Observed(name = "http.email_change")
@RestController
@RequestMapping("/api/email-change")
@RequiredArgsConstructor
public class EmailChangeResource {

    private final EmailChangeService emailChangeService;

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
    @RateLimited(key = "email_change_confirm", capacity = 10, duration = 10)
    public EmailChangeResultDto confirm(@Valid @RequestBody EmailChangeTokenDto body) {
        emailChangeService.confirm(body.token());
        return new EmailChangeResultDto("email.change.confirmed", false);
    }

    @PostMapping(value = "/undo", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(SecurityConstants.IS_PERMIT_ALL)
    @RateLimited(key = "email_change_undo", capacity = 10, duration = 10)
    public EmailChangeResultDto undo(@Valid @RequestBody EmailChangeTokenDto body) {
        return new EmailChangeResultDto("email.change.undone",
            emailChangeService.undo(body.token()).passwordResetSent());
    }
}
