package com.softropic.skillars.platform.security.api;


import com.softropic.skillars.infrastructure.message.Response;
import com.softropic.skillars.infrastructure.message.Success;
import com.softropic.skillars.platform.security.contract.ChangePasswordDto;
import com.softropic.skillars.platform.security.contract.UserDto;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.platform.security.service.UserMapper;
import com.softropic.skillars.platform.security.service.UserRegistrationService;
import com.softropic.skillars.platform.security.service.UserService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

import io.micrometer.core.annotation.Timed;
import io.micrometer.observation.annotation.Observed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * REST controller for managing the current user's account.
 */
@Observed(name = "http.account")
@RestController
@RequestMapping("/v1/account")
public class AccountResource {

    private final UserService userService;

    private final UserRegistrationService userRegistrationService;

    private final UserMapper userMapper;

    private final AccountManagementFacade accountManagementFacade;

    public AccountResource(UserService userService,
                           UserRegistrationService userRegistrationService,
                           UserMapper userMapper,
                           AccountManagementFacade accountManagementFacade) {
        this.userService = userService;
        this.userRegistrationService = userRegistrationService;
        this.userMapper = userMapper;
        this.accountManagementFacade = accountManagementFacade;
    }

    /**
     * POST  /register to register the user.
     * @param userDTO holds the user's data
     * @return ResponseEntity
     */
    @PostMapping(value="/register", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success registerAccount(@Valid @RequestBody final UserDto userDTO)  {
        final String emailSendId = accountManagementFacade.registerAccount(userDTO);
        final String msg = "You will receive an email shortly with an activation key or else contact support with the help code";
        return new Success(emailSendId, "user.creation.feedback", msg, Map.of());
    }

    /**
     * POST  /regislink to register the user.
     * Endpoint to resend the registration link
     *
     * @return ResponseEntity
     */
    @PostMapping(value="/regislink", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success registerLinkResend(@NotNull String login, @NotNull String password)  {
        final String emailSendId = accountManagementFacade.resendRegistrationLink(login, password);
        final String msg = "You will receive an email shortly with an activation key or else contact support with the help code";
        return new Success(emailSendId, "user.creation.feedback", msg, Map.of());
    }

    /**
     * GET  /activate to activate the registered user.
     * @param key is the string used for activation
     * @return response status
     */
    @PostMapping(value = "/activate",  produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success activateAccount(@RequestParam("key")final  String key) {
        return userRegistrationService.activateUser(key)
                          .map(u -> new Success(null,
                                                "user.activation.success",
                                                "Account has been activated",
                                                Map.of()))
                          .orElseThrow(() -> new AuthorizationException("Activation key invalid or already used",
                                                                        SecurityError.INVALID_ACTIVATION_KEY));
    }

    /**
     * GET  /authenticate to check if the user is authenticated, and return its login.
     * @param request httpServletRequest
     * @return remote user
     */
    @GetMapping(value = "/authenticate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public String getAuthenticatedUser(final HttpServletRequest request) {
        return request.getRemoteUser();
    }

    /**
     * GET  / to get the current user.
     * @return Http status code
     */
    @GetMapping(value = "/", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public ResponseEntity<UserDto> getAccount() {
        return Optional.ofNullable(userService.getUserWithAuthorities())
            .map(user -> new ResponseEntity<>(userMapper.toUserDto(user), HttpStatus.OK))
                .orElse(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
    }


    @PostMapping(value = "/reset_password/init", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public ResponseEntity<Response> requestPasswordReset(@RequestBody @Valid ChangePasswordDto changePasswordDto) {
        final String code = accountManagementFacade.sendPasswordResetMail(changePasswordDto);
        return new ResponseEntity<>(new Success(code,
                                                "password.reset.emailed",
                                                "Check your email for a link to reset your password",
                                                Map.of()), HttpStatus.ACCEPTED);
    }

    @PostMapping(value = "/reset_password/finish", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Success finishPasswordReset(@RequestBody @Valid KeyAndPasswordDto keyAndPassword) {//password.reset.success
        accountManagementFacade.finishPasswordReset(keyAndPassword);
        return new Success(null,
                           "password.reset.success",
                           "Your password has now been reset. You can now login with your new password.",
                           Map.of());
    }

    @GetMapping(value = "/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    @Timed
    public Map<String, String> pong() {
        return Map.of("server", "up");
    }

}
