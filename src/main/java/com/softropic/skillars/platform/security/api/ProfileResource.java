package com.softropic.skillars.platform.security.api;

import com.softropic.skillars.infrastructure.message.Failure;
import com.softropic.skillars.infrastructure.message.Response;
import com.softropic.skillars.infrastructure.message.Success;
import com.softropic.skillars.platform.security.api.dto.AddressDto;
import com.softropic.skillars.platform.security.api.dto.ChangePasswordRequestDto;
import com.softropic.skillars.platform.security.api.dto.ChangePhoneDto;
import com.softropic.skillars.platform.security.api.dto.Toggle2faDto;
import com.softropic.skillars.platform.security.api.dto.UpdateUserInfoDto;
import com.softropic.skillars.platform.security.repo.Address;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.platform.security.service.UserProfileService;
import com.softropic.skillars.platform.security.service.UserService;
import com.softropic.skillars.platform.security.service.UserMapper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;
import jakarta.validation.Valid;

/**
 * REST controller for managing the current user's profile.
 * Endpoints are mapped at /api/account/* as specified in the ROADMAP.
 */
@Observed(name = "http.profile")
@RestController
@RequestMapping("/api/account")
@PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
public class ProfileResource {

    private final Logger log = LoggerFactory.getLogger(ProfileResource.class);

    private final UserService userService;

    private final UserMapper userMapper;

    private final AccountManagementFacade accountManagementFacade;

    private final UserProfileService userProfileService;

    public ProfileResource(UserService userService,
                           UserMapper userMapper,
                           AccountManagementFacade accountManagementFacade,
                           UserProfileService userProfileService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.accountManagementFacade = accountManagementFacade;
        this.userProfileService = userProfileService;
    }

    /**
     * GET /api/account/profile to get the current user's profile.
     * @return user profile data
     */
    @GetMapping(value = "/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public ResponseEntity<UserDto> getProfile() {
        return Optional.ofNullable(userService.getUserWithAuthoritiesAndAddresses())
            .map(user -> new ResponseEntity<>(userMapper.toUserDto(user), HttpStatus.OK))
            .orElse(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
    }

    /**
     * PUT /api/account/email to update the current user's email.
     * Sends verification email to the new address.
     * @param changeEmailDto contains old email, new email, and password for verification
     * @return success or failure response
     */
    @PutMapping(value = "/email", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Response updateEmail(@Valid @RequestBody ChangeEmailDto changeEmailDto) {
        final String code = accountManagementFacade.changeEmail(
            changeEmailDto.getOldEmail(),
            changeEmailDto.getNewEmail(),
            changeEmailDto.getPassword());
        if (StringUtils.isNotBlank(code)) {
            return new Success(code, "email.updated", "Your email address has been updated", Map.of());
        }
        return new Failure(UUID.randomUUID().toString(), "email.change.failure", "Your email cannot be changed");
    }

    /**
     * PUT /api/account/password to change the current user's password.
     * @param dto contains current password and new password
     * @return success response
     */
    @PutMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success updatePassword(@Valid @RequestBody ChangePasswordRequestDto dto) {
        userProfileService.changePassword(dto.getCurrentPassword(), dto.getNewPassword())
            .orElseThrow(() -> new AuthorizationException("User not found", SecurityError.USER_NOT_FOUND));
        return new Success(null, "password.changed", "Your password has been changed", Map.of());
    }

    /**
     * PUT /api/account/phone to update the current user's phone number.
     * @param dto contains the new phone number
     * @return success response
     */
    @PutMapping(value = "/phone", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success updatePhone(@Valid @RequestBody ChangePhoneDto dto) {
        userProfileService.updatePhone(dto.getPhone())
            .orElseThrow(() -> new AuthorizationException("User not found", SecurityError.USER_NOT_FOUND));
        return new Success(null, "phone.changed", "Your phone number has been updated", Map.of());
    }

    /**
     * PUT /api/account/address to update the current user's address.
     * @param dto contains all address fields
     * @return success response
     */
    @PutMapping(value = "/address", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success updateAddress(@Valid @RequestBody AddressDto dto) {
        Address address = new Address();
        address.setName(dto.getName());
        address.setCompanyName(dto.getCompanyName());
        address.setAddressLine1(dto.getAddressLine1());
        address.setAddressLine2(dto.getAddressLine2());
        address.setAddressLine3(dto.getAddressLine3());
        address.setCity(dto.getCity());
        address.setStateProvince(dto.getStateProvince());
        address.setPostalCode(dto.getPostalCode());
        address.setCountry(dto.getCountry());

        userProfileService.updatePostalAddress(address)
            .orElseThrow(() -> new AuthorizationException("User not found", SecurityError.USER_NOT_FOUND));
        return new Success(null, "address.changed", "Your address has been updated", Map.of());
    }

    /**
     * PUT /api/account/info to update the current user's core information.
     * @param dto contains firstName, lastName, langKey, nationalId, gender, title
     * @return success response
     */
    @PutMapping(value = "/info", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success updateInfo(@Valid @RequestBody UpdateUserInfoDto dto) {
        userProfileService.updateUserInformation(
            dto.getFirstName(),
            dto.getLastName(),
            dto.getLangKey(),
            dto.getNationalId(),
            dto.getGender(),
            dto.getTitle())
            .orElseThrow(() -> new AuthorizationException("User not found", SecurityError.USER_NOT_FOUND));
        return new Success(null, "info.changed", "Your profile information has been updated", Map.of());
    }

    /**
     * PUT /api/account/2fa to toggle two-factor authentication.
     * @param dto contains enabled flag and password for verification
     * @return success response
     */
    @PutMapping(value = "/2fa", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success toggle2fa(@Valid @RequestBody Toggle2faDto dto) {
        userProfileService.toggle2fa(dto.getEnabled(), dto.getPassword())
            .orElseThrow(() -> new AuthorizationException("User not found or password mismatch", SecurityError.EMAIL_OR_PW_MISMATCH));
        String status = dto.getEnabled() ? "enabled" : "disabled";
        return new Success(null, "2fa.changed", "Two-factor authentication has been " + status, Map.of());
    }

}
