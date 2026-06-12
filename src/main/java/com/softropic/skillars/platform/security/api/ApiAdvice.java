package com.softropic.skillars.platform.security.api;


import com.softropic.skillars.infrastructure.exception.ApplicationException;
import com.softropic.skillars.infrastructure.exception.ResourceNotFoundException;
import com.softropic.skillars.infrastructure.message.EmailTokenErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorDto;
import com.softropic.skillars.infrastructure.message.ErrorMsg;
import com.softropic.skillars.infrastructure.blobstore.contract.BlobstoreErrorCode;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageObjectNotFoundException;
import com.softropic.skillars.infrastructure.blobstore.contract.exception.StorageProviderException;
import com.softropic.skillars.platform.filestorage.contract.FileStorageErrorCode;
import com.softropic.skillars.platform.filestorage.contract.exception.QuotaExceededException;
import com.softropic.skillars.platform.filestorage.contract.exception.StorageValidationException;
import com.softropic.skillars.platform.security.contract.event.SecurityAlertEvent;
import com.softropic.skillars.infrastructure.security.event.BadCredentialsEvent;
import com.softropic.skillars.platform.security.contract.exception.CoachRegistrationException;
import com.softropic.skillars.platform.security.contract.exception.FeatureGatedException;
import com.softropic.skillars.platform.security.contract.exception.UserNotFoundException;
import com.softropic.skillars.platform.security.contract.exception.LoginRateLimitedException;
import com.softropic.skillars.platform.security.contract.exception.SkillarsAccountNotVerifiedException;
import com.softropic.skillars.platform.security.contract.exception.ParentRegistrationException;
import com.softropic.skillars.platform.security.contract.exception.ShadowAccountException;
import com.softropic.skillars.platform.security.contract.exception.EmailTokenException;
import com.softropic.skillars.platform.security.contract.exception.OtpVerificationException;
import com.softropic.skillars.platform.security.contract.exception.ProfileActionException;
import com.softropic.skillars.infrastructure.security.AuthorizationException;
import com.softropic.skillars.platform.security.contract.exception.InvalidJWTDataException;
import com.softropic.skillars.platform.security.contract.exception.JWTExpiredException;
import com.softropic.skillars.platform.security.contract.exception.JWTTheftException;
import com.softropic.skillars.platform.security.contract.exception.OperationNotAllowedException;
import com.softropic.skillars.platform.security.contract.exception.SecException;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.infrastructure.security.event.FraudEvent;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.sqids.Sqids;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import static net.logstash.logback.argument.StructuredArguments.entries;
import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * As the name implies, this class handles exceptions thrown at the controller level, however, this class can be indirectly invoked from filters.
 * Pass an object of type HandlerExceptionResolver through the filter constructor;
 * See 'JWTAuthenticationFilter'
 */

@Slf4j
@RestControllerAdvice
public class ApiAdvice {
    private static final Sqids SQIDS = Sqids.builder().alphabet("ZG8K7aeb9hALF3OcTw5SNMQqC1oVJvtEsljDnIfx0zyH2rdRpmYUkP46guXiBW").build();

    private final MessageSource messageSource;

    private final ApplicationEventPublisher publisher;

    public ApiAdvice(MessageSource messageSource, ApplicationEventPublisher publisher) {
        this.messageSource = messageSource;
        this.publisher = publisher;
    }

    /**
     * Handles HTTP method not supported (405). Spring raises HttpRequestMethodNotSupportedException
     * when a request uses an HTTP method not mapped on the target controller.
     * Without this handler the default Throwable handler returns 500 instead of 405.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorDto methodNotSupportedHandler(final HttpRequestMethodNotSupportedException exception) {
        final String defaultMsg = "HTTP method not supported";
        return logErrorAndReturnDTO(exception, defaultMsg, "generic.methodNotAllowed");
    }

    /**
     * Default handler for all exception which are not caught by the handlers below.
     *
     * @param exception occurred exception
     * @return error message
     */
    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) //unknown exceptions should be treated as internal errors
    public ErrorDto defaultErrorHandler(final Exception exception) {
        final String defaultMsg = "An unknown Exception has occurred";
        return logErrorAndReturnDTO(exception, defaultMsg,"generic.unknown");
    }

    //This seems to be the best way to handle constraint exceptions that cannot be caught and handled programmatically before db-write attempts
    // Map constraint names to human-readable error keys
    private static final Map<String, String> CONSTRAINT_MAPPINGS = Map.of(
        "tenant_tenant_ref_key", "tenant.ref.duplicate",
        "user_login_key", "user.login.duplicate",
        "user_email_key", "user.email.duplicate"
    );

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDto integrityViolationHandler(final DataIntegrityViolationException dive) {
        final Throwable throwable = dive.getCause();
        if (throwable instanceof org.hibernate.exception.ConstraintViolationException cve) {
            final String constraintName = cve.getConstraintName();
            final String messageKey = CONSTRAINT_MAPPINGS.getOrDefault(constraintName, "generic.dataError");
            
            // Log the raw constraint name for internal debugging, but return a sanitized DTO
            log.warn("Database constraint violation", kv("constraint", constraintName));
            
            return logErrorAndReturnDTO(dive, "Data integrity error", messageKey);
        }
        return logErrorAndReturnDTO(throwable, "Data integrity error", "generic.dataError");
    }

    /**
     * Handles all auth exceptions caused by non-loggedin users or that lead to a user being logged out
     * @param runtimeException
     * @return
     */
    @ExceptionHandler({
                        AuthorizationException.class,
                      })
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorDto handleAuthException(final AuthorizationException runtimeException) {
        final String defaultMsg = "Unauthorized Access.";
        return handleSecErrorAndReturnDTO(runtimeException, defaultMsg, "security.unauthorized");
    }

    @ExceptionHandler({
                        AuthenticationException.class
                      })
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorDto handleAuthException(final AuthenticationException authenException) {
        final String defaultMsg = "Authentication issue occurred.";
        return handleSecErrorAndReturnDTO(authenException, defaultMsg, "security.authError");
    }

    @ExceptionHandler({ AccountExpiredException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorDto accountExpired(final AccountExpiredException accountExpiredException) {
        final String defaultMsg = "Your account has expired.";
        return handleSecErrorAndReturnDTO(accountExpiredException, defaultMsg, "security.accountExpired");
    }

    @ExceptionHandler({ CredentialsExpiredException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorDto credentialsExpired(final CredentialsExpiredException runtimeException) {
        final String defaultMsg = "Your credentials have expired.";
        return handleSecErrorAndReturnDTO(runtimeException, defaultMsg, "security.credExpired");
    }

    @ExceptionHandler({ DisabledException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorDto accountNotEnabled(final DisabledException runtimeException) {
        final String defaultMsg = "Your account is not enabled.";
        return handleSecErrorAndReturnDTO(runtimeException, defaultMsg, "security.accNotEnabled");
    }

    @ExceptionHandler({ LockedException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorDto accountLocked(final LockedException runtimeException) {
        final String defaultMsg = "There is an issue with your account. Check your email and contact the support team. Remember to save the help code.";
        return handleSecErrorAndReturnDTO(runtimeException, defaultMsg, "security.accLocked");
    }

    @ExceptionHandler({
                        BadCredentialsException.class
                      })
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorDto handleBadCreds(final BadCredentialsException runtimeException) {
        final String defaultMsg = "The login and password combination does not exist";
        publisher.publishEvent(new BadCredentialsEvent(defaultMsg));
        return handleSecErrorAndReturnDTO(runtimeException, defaultMsg, "security.badCreds");
    }

    @ExceptionHandler({
                        UsernameNotFoundException.class
                      })
    @ResponseStatus(HttpStatus.OK)
    public ErrorDto handleUserNotFound(final UsernameNotFoundException runtimeException) {
        final String defaultMsg = "The login and password combination does not exist";
        return handleSecErrorAndReturnDTO(runtimeException, defaultMsg, "security.badCreds"); //(runtimeException, defaultMsg, "login.success");
        //This option may be more secure but the UX is not that good
        //return new Success(errorDTO.getHelpCode(), "login.success", "Check your email for the sent OTP.", Map.of()); //This message should be same as when a login is successful. Send an email stating the error

    }

    @ExceptionHandler({
            AccessDeniedException.class
    })
    @ResponseStatus(HttpStatus.FORBIDDEN) //could occur when method security throws exception
    public ErrorDto accessDeniedHandler(final AccessDeniedException exception) {
        final String defaultMsg = "You do not have the required rights. You can contact help desk";
        final ErrorDto errorDTO = logErrorAndReturnDTO(exception, defaultMsg, "security.unauthorized");
        publisher.publishEvent(new SecurityAlertEvent(exception, errorDTO.getHelpCode()));
        return errorDTO;
    }

    @ExceptionHandler({
            OperationNotAllowedException.class
    })
    @ResponseStatus(HttpStatus.FORBIDDEN) //could occur when method security throws exception
    public ErrorDto operationDeniedHandler(final OperationNotAllowedException exception) {
        final String defaultMsg = "The operation is not granted. You can contact help desk";
        return handleSecErrorAndReturnDTO(exception, defaultMsg, "security.opForbidden");
    }

    @ExceptionHandler({
            InvalidJWTDataException.class,
            JWTTheftException.class
    })
    @ResponseStatus(HttpStatus.FORBIDDEN) //could occur when method security throws exception
    public ErrorDto fraudHandler(final AuthorizationException exception) {
        publisher.publishEvent(new FraudEvent("AuthorizationException of type %s has been thrown.".formatted(exception.getClass().getSimpleName())));
        final String defaultMsg = "Access has been denied. You can contact help desk";
        return handleSecErrorAndReturnDTO(exception, defaultMsg, "security.opForbidden");
    }

    @ExceptionHandler({
            ProfileActionException.class
    })
    @ResponseStatus(HttpStatus.FORBIDDEN) //could occur when method security throws exception
    public ErrorDto changeDenialHandler(final ProfileActionException exception) {
        final String defaultMsg = "Access has been denied. You can contact help desk";
        return handleSecErrorAndReturnDTO(exception, defaultMsg, "security.opForbidden");
    }

    @ExceptionHandler(JWTExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED) //could occur when method security throws exception
    public ErrorDto jwtExpirationHandler(final JWTExpiredException exception) {
        final String defaultMsg = "Your session is no longer valid. You need to sign-in again";
        return handleSecErrorAndReturnDTO(exception, defaultMsg, "security.sessionExpired");
    }

    @ExceptionHandler(SkillarsAccountNotVerifiedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorDto accountNotVerifiedHandler(final SkillarsAccountNotVerifiedException ex) {
        return handleSecErrorAndReturnDTO(ex, ex.getMessage(), "security.accountNotVerified");
    }

    @ExceptionHandler(LoginRateLimitedException.class)
    public ResponseEntity<ErrorDto> loginRateLimitedHandler(final LoginRateLimitedException ex) {
        ErrorDto body = handleSecErrorAndReturnDTO(ex, ex.getMessage(), "security.accountLocked");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(FeatureGatedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorDto featureGatedHandler(final FeatureGatedException ex) {
        log.warn("Feature gate blocked: feature={} requiredTier={}", ex.getFeatureKey(), ex.getRequiredTier());
        return logErrorAndReturnDTO(ex, ex.getMessage(), "security.featureGated");
    }

    @ExceptionHandler(SecException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED) //could occur when method security throws exception
    public ErrorDto secExceptionHandler(final SecException exception) {
        final String defaultMsg = "Internal unknown exception. You can contact help desk with your help code";
        return handleSecErrorAndReturnDTO(exception, defaultMsg, "security.generic");
    }


    /**
     * Exception occurs when Spring cannot convert parameters into required type. E.g. "abcd" into UUID
     *
     * @param matme occurred exception
     * @return error message
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDto typeMismatchExceptionHandler(final MethodArgumentTypeMismatchException matme) {
        final String defaultMsg = "Method argument mismatch.";
        final String paramName = getParameterNameNullSafe(matme.getName());
        return logErrorAndReturnDTO(matme, defaultMsg, "validation.badRequest", paramName);
    }

    /**
     * Missing parameter exception handler.
     *
     * @param msrpe missing parameter exception
     * @return error message
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDto missingParameterExceptionHandler(final MissingServletRequestParameterException msrpe) {
        final String defaultMsg = "Missing parameter.";
        final String paramName = getParameterNameNullSafe(msrpe.getParameterName());
        return logErrorAndReturnDTO(msrpe, defaultMsg, "validation.badRequest", paramName);
    }

    /**
     * Invalid request body payload handler. E.g. invalid json format.
     *
     * @param hmnre invalid payload exception
     * @return error message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDto httpMessageNotReadableExceptionHandler(final HttpMessageNotReadableException hmnre) {
        final String defaultMsg = "Payload is not readable.";
        return logErrorAndReturnDTO(hmnre, defaultMsg, "validation.badRequest");
    }

    /**
     * Handles the exception thrown when validation on an argument annotated with @Valid fails
     * It is a spring specific exception for bean validation (JSR-303, 330, 380)
     * @param manve MethodArgumentNotValidException
     * @return errorMap
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDto methodArgumentNotValidExceptionHandler(final MethodArgumentNotValidException manve) {
        final String helpCode = logError(manve, "Data validation error");
        final BindingResult bindingResult = manve.getBindingResult();
        return processFieldErrors(helpCode, bindingResult.getFieldErrors());
    }

    /**
     * Handles validation exceptions that are thrown by the JPA impl (e.g. hibernate) just before any C, R, U, or D
     * If the input is validated earlier at a method level, then MethodArgumentNotValidException is thrown
     * @param cve
     * @return
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDto constraintViolationExceptionHandler(final ConstraintViolationException cve) {
        final String defaultMsg = "Data violation error";
        final Optional<ConstraintViolation<?>> violationOpt = cve.getConstraintViolations().stream().findFirst();
        final String msgKey = violationOpt.map(ConstraintViolation::getMessageTemplate).orElse(null);
        return logErrorAndReturnDTO(cve, defaultMsg, msgKey);
    }

    private ErrorDto processFieldErrors(String helpCode, List<FieldError> fieldErrors) {
        final ErrorDto dto = new ErrorDto(helpCode, new ErrorMsg("validation.invalidData", "Invalid Data"));
        final String chosenLang = RequestMetadataProvider.getClientInfo().getChosenLang();

        fieldErrors = deduplicate(fieldErrors);
        String message;
        String errorKey;
        String fallbackMessage;
        String field;
        final String defaultKeyPrefix = "invalid.";
        for (final FieldError fieldError : fieldErrors) {
            field = fieldError.getField();
            String defaultMsg = fieldError.getDefaultMessage();

            // Check if defaultMessage contains our custom format: "errorKey|fallbackMessage"
            if (defaultMsg != null && defaultMsg.contains("|")) {
                String[] parts = defaultMsg.split("\\|", 2);
                errorKey = parts[0];
                fallbackMessage = parts.length > 1 ? parts[1] : defaultMsg;
            } else {
                // Standard validation: use "invalid.fieldName" as key
                errorKey = defaultKeyPrefix + field;
                fallbackMessage = defaultMsg;
            }

            // Look up translated message using the errorKey
            message = messageSource.getMessage(errorKey,
                                               null,
                                               fallbackMessage,
                                               Locale.forLanguageTag(chosenLang));

            dto.add(fieldError.getObjectName(), field, new ErrorMsg(errorKey, message));
        }
        return dto;
    }


    @ExceptionHandler(CoachRegistrationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorDto coachRegistrationExceptionHandler(final CoachRegistrationException ex) {
        return logErrorAndReturnDTO(ex, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(ParentRegistrationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorDto parentRegistrationExceptionHandler(final ParentRegistrationException ex) {
        return logErrorAndReturnDTO(ex, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(ShadowAccountException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorDto shadowAccountExceptionHandler(final ShadowAccountException ex) {
        return logErrorAndReturnDTO(ex, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(EmailTokenException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public EmailTokenErrorDto emailTokenExceptionHandler(final EmailTokenException ex) {
        final String helpCode = logError(ex, ex.getMessage());
        final String message = messageSource.getMessage(ex.getErrorCode(), null, ex.getMessage(),
            Locale.forLanguageTag(RequestMetadataProvider.getClientInfo().getChosenLang()));
        return new EmailTokenErrorDto(helpCode, new ErrorMsg(ex.getErrorCode(), message), ex.isCanResend());
    }

    @ExceptionHandler(OtpVerificationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorDto otpVerificationExceptionHandler(final OtpVerificationException ex) {
        return logErrorAndReturnDTO(ex, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(StorageObjectNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorDto storageObjectNotFoundHandler(final StorageObjectNotFoundException ex) {
        return logErrorAndReturnDTO(ex, "storage.objectNotFound", BlobstoreErrorCode.STORAGE_OBJECT_NOT_FOUND.getErrorCode());
    }

    @ExceptionHandler(QuotaExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ErrorDto quotaExceededHandler(final QuotaExceededException ex) {
        return logErrorAndReturnDTO(ex, "storage.quotaExceeded", FileStorageErrorCode.QUOTA_EXCEEDED.getErrorCode());
    }

    @ExceptionHandler(StorageValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorDto storageValidationHandler(final StorageValidationException ex) {
        return logErrorAndReturnDTO(ex, "storage.validationFailed", FileStorageErrorCode.VALIDATION_FAILED.getErrorCode());
    }

    @ExceptionHandler(StorageProviderException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ErrorDto storageProviderHandler(final StorageProviderException ex) {
        return logErrorAndReturnDTO(ex, "storage.providerError", BlobstoreErrorCode.PROVIDER_ERROR.getErrorCode());
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorDto userNotFoundHandler(final UserNotFoundException ex) {
        return logErrorAndReturnDTO(ex, ex.getMessage(), "security.userNotFound");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorDto resourceNotFoundExceptionHandler(final ResourceNotFoundException rnfe) {
        final String defaultMsg = "The resource cannot be found";
        final String resourceName = rnfe.getResourceName();
        return logErrorAndReturnDTO(rnfe, defaultMsg, rnfe.getErrorCode().getErrorCode(), resourceName);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorDto entityNotFoundExceptionHandler(final EntityNotFoundException enfe) {
        final String defaultMsg = "The requested entity could not be found";
        return logErrorAndReturnDTO(enfe, defaultMsg, "generic.notFound");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorDto illegalStateExceptionHandler(final IllegalStateException ise) {
        final String defaultMsg = "The requested operation conflicts with current state";
        return logErrorAndReturnDTO(ise, defaultMsg, "generic.conflict");
    }

    /**
     * Handles concurrent rotation races (AKEY-09). When two threads both attempt to
     * rotate the same TenantApiKey, Hibernate's version-conditional UPDATE matches
     * zero rows on the loser, and Spring Data wraps the JPA OptimisticLockException
     * in ObjectOptimisticLockingFailureException. Map it to 409 Conflict.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorDto optimisticLockExceptionHandler(
            final org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        final String defaultMsg = "Concurrent modification conflict — please retry";
        return logErrorAndReturnDTO(ex, defaultMsg, "generic.conflict");
    }



    private List<FieldError> deduplicate(List<FieldError> fieldErrors) {
        Map<String, FieldError> errorMap = new HashMap<>();
        fieldErrors.forEach(fe -> errorMap.put(fe.getField(), fe));
        return List.copyOf(errorMap.values());
    }

    private ErrorDto handleSecErrorAndReturnDTO(AuthenticationException exception, String defaultMsg, String msgKey, String... args) {
        final ErrorDto errorDTO = logErrorAndReturnDTO(exception, defaultMsg, msgKey, args);
        publisher.publishEvent(new SecurityAlertEvent(exception, errorDTO.getHelpCode()));
        return errorDTO;
    }

    private ErrorDto handleSecErrorAndReturnDTO(AuthorizationException exception, String defaultMsg, String msgKey, String... args) {
        final ErrorDto errorDTO = logErrorAndReturnDTO(exception, defaultMsg, msgKey, args);
        publisher.publishEvent(new SecurityAlertEvent(exception, errorDTO.getHelpCode()));
        return errorDTO;
    }

    private ErrorDto handleSecErrorAndReturnDTO(SecException exception, String defaultMsg, String msgKey, String... args) {
        final ErrorDto errorDTO = logErrorAndReturnDTO(exception, defaultMsg, msgKey, args);
        publisher.publishEvent(new SecurityAlertEvent(exception, errorDTO.getHelpCode()));
        return errorDTO;
    }


    private ErrorDto logErrorAndReturnDTO(Throwable throwable, String defaultMsg, String msgKey, String... args) {
        final String helpCode = logError(throwable, defaultMsg);
        return toErrorDTO(msgKey, defaultMsg, helpCode, args);
    }

    private ErrorDto toErrorDTO(final String msgKey, final String defaultMessage, String helpCode, final Object... args) {
        final String chosenLang = RequestMetadataProvider.getClientInfo().getChosenLang();
        String message = defaultMessage;
        if(StringUtils.isNotBlank(msgKey)) {
            message = messageSource.getMessage(msgKey,
                                               args,
                                               defaultMessage,
                                               Locale.forLanguageTag(chosenLang));
        }
        return new ErrorDto(helpCode, new ErrorMsg(msgKey, message));

    }

    private String getParameterNameNullSafe(final String paramName) {
        return paramName != null ? paramName : "unknown";
    }

    private String logError(Throwable throwable, String msgTemplate)  {
        String errorCode;
        Map<String, Object> ctx = new HashMap<>();
        if(throwable instanceof ApplicationException applicationException) {
            errorCode = applicationException.getSupportId();
            //Get and log the context as well
            ctx = applicationException.getLogContext();
        } else {
            errorCode = SQIDS.encode(List.of(Integer.toUnsignedLong(UUID.randomUUID().hashCode())));
        }

        final String templateWithSupportId = msgTemplate + " SUPPORT_ID: %s";
        final String fullMsg = String.format(templateWithSupportId, errorCode);
        log.error(fullMsg, entries(ctx), throwable);
        return errorCode;
    }
}
