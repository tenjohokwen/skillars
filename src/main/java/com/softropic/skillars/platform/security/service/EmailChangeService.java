package com.softropic.skillars.platform.security.service;

import com.softropic.skillars.infrastructure.security.RateLimitingService;
import com.softropic.skillars.infrastructure.security.SecurityConstants;
import com.softropic.skillars.infrastructure.security.SecurityError;
import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.infrastructure.util.RandomUtil;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.security.contract.LoginIdType;
import com.softropic.skillars.platform.security.contract.SecurityProperties;
import com.softropic.skillars.platform.security.contract.event.AccountChangeEvent;
import com.softropic.skillars.platform.security.contract.event.AccountChangeUserInfo;
import com.softropic.skillars.platform.security.contract.exception.EmailChangeLinkInvalidException;
import com.softropic.skillars.platform.security.contract.exception.ProfileActionException;
import com.softropic.skillars.platform.security.contract.exception.RecentEmailChangeException;
import com.softropic.skillars.platform.security.repo.EmailChangeRequest;
import com.softropic.skillars.platform.security.repo.EmailChangeRequestRepository;
import com.softropic.skillars.platform.security.repo.RefreshTokenRepository;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Changing the email an account signs in with (skillars-deferred-172).
 *
 * <ol>
 *   <li><b>Request</b> (signed in, password re-checked): nothing changes on the account. A link goes to the
 *       new address ({@code EMAIL_CHANGE}, 24 h). If the address is taken, its holder is told instead
 *       ({@code EMAIL_CHANGE_ADDRESS_IN_USE}) and the caller gets the very same answer (D1).</li>
 *   <li><b>Confirm</b> (public link): email and, for an email-login account, login switch to the new address;
 *       every session is revoked; the old address gets an undo link ({@code EMAIL_CHANGED_NOTICE}, 7 days) and
 *       stays reserved for that window.</li>
 *   <li><b>Undo</b> (public link): the old address and login come back, every session is revoked, every other
 *       open request and undo link of the account dies, and the password is replaced by an unusable value with
 *       a reset link mailed to the restored address, so whoever changed it is locked out. On a suspended (or
 *       not activated) account the suspension is left alone (R2): the password is still replaced and any pending
 *       reset key cleared, but no reset link is sent until it is reinstated. The address the account leaves is
 *       told the change was undone, with a help code.</li>
 * </ol>
 *
 * <p>One change per undo window (R1): while a confirmed change can still be undone, a new request is refused.
 * Every mail goes through the transactional outbox, inside the transaction that decided to send it.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EmailChangeService {

    static final Duration CONFIRM_TTL = Duration.ofHours(24);
    static final Duration UNDO_TTL = Duration.ofDays(7);

    // Per account, across IPs: the endpoint's own @RateLimited bucket is per IP. Exhaustion answers 429; it
    // concerns only the caller's own account, so it reveals nothing about the target address.
    static final String REQUEST_LIMIT_KEY = "email_change_request";
    static final long REQUEST_CAPACITY = 3;
    static final long REQUEST_WINDOW_MINUTES = 60;

    // Per account, every attempt, consumed BEFORE the password check: the endpoint re-checks the password, so
    // without this a stolen or left-open session could guess it here bounded only by the per-IP limit.
    static final String ATTEMPT_LIMIT_KEY = "email_change_attempt";
    static final long ATTEMPT_CAPACITY = 10;
    static final long ATTEMPT_WINDOW_MINUTES = 60;

    // Confirmation links to one address: 3 a day per requesting account, 10 a day in all. The pair limit stops
    // one account mailing someone's inbox over and over, and alone it cannot use up the address's allowance. The
    // total caps what several accounts together can send to one inbox; four or more accounts acting together can
    // still use it up and so silently delay the address's owner by a day (accepted, owner decision F3). Exhaustion
    // is silent (same answer, nothing sent), so it is no oracle. The 24 h windows rely on the bucket idle TTL
    // (default 24 h) not being lowered.
    static final String CONFIRM_MAIL_LIMIT_KEY = "email_change_confirm_mail";
    static final long CONFIRM_MAIL_CAPACITY = 3;
    static final String CONFIRM_MAIL_TARGET_LIMIT_KEY = "email_change_confirm_mail_target";
    static final long CONFIRM_MAIL_TARGET_CAPACITY = 10;
    static final long CONFIRM_MAIL_WINDOW_HOURS = 24;

    // Per holder account, like registration's CREATION_DUP notice. Exhaustion is silent.
    static final String IN_USE_NOTICE_LIMIT_KEY = "email_change_address_in_use_notice";
    static final long IN_USE_NOTICE_CAPACITY = 2;
    static final long IN_USE_NOTICE_WINDOW_MINUTES = 60;

    private static final String ERASED_EMAIL_DOMAIN = "@erased.io";

    private final UserRepository userRepository;
    private final EmailChangeRequestRepository requestRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailAddressAvailability availability;
    private final SecurityUtil securityUtil;
    private final PasswordEncoder passwordEncoder;
    private final RateLimitingService rateLimitingService;
    private final NotificationOutboxSupport notificationOutboxSupport;
    private final FrontendLinkBuilder frontendLinks;
    private final SecurityProperties securityProperties;
    private final ApplicationEventPublisher publisher;

    /** What an undo did, so the page can word its answer. */
    public record UndoOutcome(boolean passwordResetSent) {
    }

    /**
     * Starts a change for the signed-in account. Returns normally whether or not the new address is free; only
     * a wrong password or current address ({@code EMAIL_OR_PW_MISMATCH}), the per-account limit (429) and an
     * open undo window ({@link RecentEmailChangeException}) are refused, none of which depends on the target.
     */
    @PreAuthorize(SecurityConstants.HAS_ANY_ROLE)
    public void request(final String oldEmail, final String newEmail, final String password) {
        final Instant now = Instant.now(ClockProvider.getClock());
        final User user = userRepository.findOneByLogin(securityUtil.getCurrentUser().getUsername())
            .orElseThrow(() -> mismatch(oldEmail, newEmail, false, false));
        if (!rateLimitingService.tryConsume(String.valueOf(user.getId()), ATTEMPT_LIMIT_KEY, ATTEMPT_CAPACITY,
                ATTEMPT_WINDOW_MINUTES, TimeUnit.MINUTES)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "email.change.tooManyRequests");
        }
        final boolean passwordMatch = password != null && passwordEncoder.matches(password, user.getPassword());
        final boolean emailMatch = StringUtils.equalsIgnoreCase(StringUtils.trim(oldEmail), user.getEmail());
        if (!passwordMatch || !emailMatch) {
            throw mismatch(oldEmail, newEmail, passwordMatch, emailMatch);
        }
        if (!rateLimitingService.tryConsume(String.valueOf(user.getId()), REQUEST_LIMIT_KEY, REQUEST_CAPACITY,
                REQUEST_WINDOW_MINUTES, TimeUnit.MINUTES)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "email.change.tooManyRequests");
        }
        // One request at a time per account (NO_WAIT; a concurrent one answers 409 generic.conflict whatever its
        // target). Without it, two requests sent at once would tell whether an address is registered: only a free
        // target inserts a row, and only that insert collides with the other's in ux_ecr_one_open_per_user.
        userRepository.findByIdForUpdate(user.getId());
        final Optional<Instant> windowEnd = requestRepository.findOpenUndoWindowEnd(user.getId(), now);
        if (windowEnd.isPresent()) {
            throw new RecentEmailChangeException(RecentEmailChangeException.EMAIL_CHANGE_KEY, windowEnd.get());
        }

        final String target = newEmail.trim().toLowerCase(Locale.ROOT);
        // F1: the previous link dies on EVERY path below. If it survived only when the new address was taken,
        // asking for your own mailbox and then for someone else's would tell you whether theirs is registered.
        requestRepository.supersedeOpen(user.getId(), now);
        if (target.equals(user.getEmail())) {
            return;
        }
        final Optional<EmailAddressAvailability.Holder> holder = availability.holderOf(target, user.getId(), now);
        if (holder.isPresent()) {
            notifyAddressInUse(holder.get(), user.getId());
            return;
        }
        if (!rateLimitingService.tryConsume(user.getId() + ":" + target, CONFIRM_MAIL_LIMIT_KEY,
                CONFIRM_MAIL_CAPACITY, CONFIRM_MAIL_WINDOW_HOURS, TimeUnit.HOURS)
            || !rateLimitingService.tryConsume(target, CONFIRM_MAIL_TARGET_LIMIT_KEY, CONFIRM_MAIL_TARGET_CAPACITY,
                CONFIRM_MAIL_WINDOW_HOURS, TimeUnit.HOURS)) {
            log.info("[EMAIL_CHANGE_CONFIRM_MAIL_LIMITED] confirmation mail skipped", kv("userId", user.getId()));
            return;
        }

        final String confirmToken = EmailChangeTokens.generate();
        final EmailChangeRequest request = new EmailChangeRequest();
        request.setUserId(user.getId());
        request.setOldEmail(user.getEmail());
        request.setOldLogin(user.getLogin());
        request.setNewEmail(target);
        request.setConfirmTokenHash(EmailChangeTokens.hash(confirmToken));
        request.setConfirmExpiresAt(now.plus(CONFIRM_TTL));
        requestRepository.save(request);

        final Map<String, Object> data = baseData();
        data.put("confirmUrl", frontendLinks.route("email-change/confirm") + "?token=" + confirmToken);
        enqueue(EmailTemplate.EMAIL_CHANGE, recipient(user, target), data, "requestId", request.getId());
    }

    /**
     * Applies a requested change. Refused as an invalid link when the request is not open, the account is
     * suspended (R2), its email is no longer the one the request was made from (R1), or the new address became
     * taken meanwhile; in those three cases the request is also ended, which is why this exception commits.
     */
    @Transactional(noRollbackFor = EmailChangeLinkInvalidException.class)
    public void confirm(final String token) {
        final Instant now = Instant.now(ClockProvider.getClock());
        final EmailChangeRequest request = findByToken(token, requestRepository::findByConfirmTokenHash)
            .filter(r -> r.isConfirmable(now))
            .orElseThrow(() -> new EmailChangeLinkInvalidException("confirm link unknown, used, superseded or expired"));
        final User user = userRepository.findById(request.getUserId())
            .orElseThrow(() -> new EmailChangeLinkInvalidException("account gone"));
        final String refusal = user.isLocked() ? "account locked"
            : !StringUtils.equalsIgnoreCase(request.getOldEmail(), user.getEmail()) ? "account email changed since"
            : availability.holderOf(request.getNewEmail(), user.getId(), now).isPresent() ? "address taken since"
            : null;
        if (refusal != null) {
            requestRepository.supersede(request.getId(), now);
            log.info("[EMAIL_CHANGE_CONFIRM_REFUSED] request ended", kv("requestId", request.getId()),
                kv("userId", user.getId()), kv("reason", refusal));
            throw new EmailChangeLinkInvalidException(refusal);
        }

        final String undoToken = EmailChangeTokens.generate();
        if (requestRepository.claimConfirm(request.getId(), EmailChangeTokens.hash(undoToken), now.plus(UNDO_TTL),
                now) != 1) {
            throw new EmailChangeLinkInvalidException("confirm link used concurrently");
        }
        // Refresh tokens REQUIRES_NEW, on rows this transaction has not touched; then the user row (re-read:
        // the claim above cleared the persistence context) and the session epoch inside this transaction.
        refreshTokenRepository.markAllUsedByUserId(user.getId());
        final User account = userRepository.findById(user.getId()).orElseThrow();
        account.setEmail(request.getNewEmail());
        if (account.getLoginIdType() == LoginIdType.EMAIL) {
            account.setLogin(request.getNewEmail());
        }
        userRepository.invalidateSessionsForUserInCurrentTransaction(account.getId(), now);

        final Map<String, Object> data = baseData();
        data.put("undone", false);
        data.put("newEmailMasked", mask(request.getNewEmail()));
        data.put("undoUrl", frontendLinks.route("email-change/undo") + "?token=" + undoToken);
        data.put("undoDays", UNDO_TTL.toDays());
        enqueue(EmailTemplate.EMAIL_CHANGED_NOTICE, recipient(account, request.getOldEmail()), data,
            "requestId", request.getId());
        publisher.publishEvent(new AccountChangeEvent(AccountChangeEvent.Action.EMAIL_CHANGED,
            request.getOldEmail(), request.getNewEmail(), userInfo(account, request.getOldEmail())));
        log.info("[EMAIL_CHANGE_CONFIRMED] sign-in email changed", kv("requestId", request.getId()),
            kv("userId", account.getId()));
    }

    /** Reverts a confirmed change; see the class comment. */
    public UndoOutcome undo(final String token) {
        final Instant now = Instant.now(ClockProvider.getClock());
        final EmailChangeRequest request = findByToken(token, requestRepository::findByUndoTokenHash)
            .filter(r -> r.isUndoable(now))
            .orElseThrow(() -> new EmailChangeLinkInvalidException("undo link unknown, used or expired"));
        if (requestRepository.claimUndo(request.getId(), now) != 1) {
            throw new EmailChangeLinkInvalidException("undo link used concurrently");
        }
        // R1: whatever else was started from this account dies with the undo.
        requestRepository.supersedeOpen(request.getUserId(), now);
        requestRepository.expireOtherUndoLinks(request.getUserId(), request.getId(), now);
        refreshTokenRepository.markAllUsedByUserId(request.getUserId());

        final User account = userRepository.findById(request.getUserId()).orElseThrow();
        account.setEmail(request.getOldEmail());
        account.setLogin(request.getOldLogin());
        // Whoever made the change knows the password (they re-entered it to ask) and may hold a reset key mailed to
        // their address, so both die on every account. A suspended one keeps its suspension, and its owner resets
        // from the sign-in page once reinstated, as the notice says; until then no key can be issued for it.
        account.setPassword(passwordEncoder.encode(EmailChangeTokens.generate()));
        account.setResetKey(null);
        account.setResetExpiration(null);
        final boolean resetPassword = account.isActivated() && !account.isLocked();
        final Map<String, Object> data = baseData();
        final EmailTemplate template;
        if (resetPassword) {
            account.preparePasswordReset(RandomUtil.generateResetKey(),
                securityProperties.getPasswordResetExpiration().toHours());
            data.put("resetKey", account.getResetKey());
            template = EmailTemplate.PASSWORD_RESET;
        } else {
            data.put("undone", true);
            template = EmailTemplate.EMAIL_CHANGED_NOTICE;
        }
        userRepository.invalidateSessionsForUserInCurrentTransaction(account.getId(), now);

        enqueue(template, recipient(account, request.getOldEmail()), data, "requestId", request.getId());
        // V2: whoever reads the old mailbox can undo, and people often move away from a mailbox because it was
        // compromised. The address the account just left is told, so a real owner learns at once and has a help
        // code to quote to support.
        final Map<String, Object> away = baseData();
        away.put("revertedAway", true);
        away.put("oldEmailMasked", mask(request.getOldEmail()));
        enqueue(EmailTemplate.EMAIL_CHANGED_NOTICE, recipient(account, request.getNewEmail()), away,
            "requestId", request.getId());
        publisher.publishEvent(new AccountChangeEvent(AccountChangeEvent.Action.EMAIL_CHANGE_UNDONE,
            request.getNewEmail(), request.getOldEmail(), userInfo(account, request.getOldEmail())));
        log.info("[EMAIL_CHANGE_UNDONE] sign-in email restored", kv("requestId", request.getId()),
            kv("userId", account.getId()), kv("passwordReset", resetPassword));
        return new UndoOutcome(resetPassword);
    }

    /**
     * R3: refuses an irreversible action (erasure) while the account's last email change can still be undone.
     * Joins the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void assertNoOpenUndoWindow(final Long userId, final String errorKey) {
        requestRepository.findOpenUndoWindowEnd(userId, Instant.now(ClockProvider.getClock()))
            .ifPresent(end -> {
                throw new RecentEmailChangeException(errorKey, end);
            });
    }

    /** A password change or reset ends a pending (unconfirmed) change: its link stops working. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void endPendingRequest(final Long userId) {
        requestRepository.supersedeOpen(userId, Instant.now(ClockProvider.getClock()));
    }

    private void notifyAddressInUse(final EmailAddressAvailability.Holder holder, final Long requesterId) {
        final User owner = holder.user();
        if (owner.isLocked() && owner.getEmail() != null && owner.getEmail().endsWith(ERASED_EMAIL_DOMAIN)) {
            return; // a GDPR-erased tombstone: nobody receives mail there
        }
        if (!rateLimitingService.tryConsume(String.valueOf(owner.getId()), IN_USE_NOTICE_LIMIT_KEY,
                IN_USE_NOTICE_CAPACITY, IN_USE_NOTICE_WINDOW_MINUTES, TimeUnit.MINUTES)) {
            return;
        }
        final Map<String, Object> data = baseData();
        data.put("reserved", holder.reserved());
        enqueue(EmailTemplate.EMAIL_CHANGE_ADDRESS_IN_USE, recipient(owner, holder.notifyAddress()), data,
            "requesterId", requesterId);
    }

    private void enqueue(final EmailTemplate template, final Recipient recipient, final Map<String, Object> data,
                         final String refName, final Object refValue) {
        final String sendId = UUID.randomUUID().toString();
        notificationOutboxSupport.enqueueEmail(template, recipient, data, sendId);
        // The mail asks the reader to quote the help code; support finds the event from this line.
        log.info("[EMAIL_CHANGE_MAIL] queued", kv("template", template), kv("helpCode", data.get("helpCode")),
            kv(refName, refValue), kv("sendId", sendId));
    }

    private Map<String, Object> baseData() {
        final Map<String, Object> data = new HashMap<>();
        data.put("baseUrl", frontendLinks.base());
        data.put("helpCode", ShortCode.shortenInt(UUID.randomUUID().hashCode()));
        return data;
    }

    private static Recipient recipient(final User user, final String address) {
        final Recipient recipient = new Recipient();
        recipient.setEmail(address);
        recipient.setFirstname(user.getFirstName());
        recipient.setLastname(user.getLastName());
        recipient.setLangKey(user.getLangKey());
        recipient.setTitle(user.getTitle());
        recipient.setGender(user.getGender() != null ? user.getGender().name() : null);
        return recipient;
    }

    private static AccountChangeUserInfo userInfo(final User user, final String address) {
        return new AccountChangeUserInfo(address, user.getFirstName(), user.getLastName(), user.getLangKey(),
            user.getTitle(), user.getGender() != null ? user.getGender().name() : null);
    }

    private static Optional<EmailChangeRequest> findByToken(
            final String token, final java.util.function.Function<String, Optional<EmailChangeRequest>> finder) {
        if (StringUtils.isBlank(token)) {
            return Optional.empty();
        }
        return finder.apply(EmailChangeTokens.hash(token.trim()));
    }

    /** First character of the local part, an ellipsis, the domain; a local part of 1-2 characters shows none. */
    static String mask(final String address) {
        final int at = address.indexOf('@');
        if (at < 0) {
            return "…";
        }
        final String local = address.substring(0, at);
        return (local.length() <= 2 ? "" : local.substring(0, 1)) + "…" + address.substring(at);
    }

    private static ProfileActionException mismatch(final String oldEmail, final String newEmail,
                                                   final boolean passwordMatch, final boolean emailMatch) {
        return new ProfileActionException("Cannot update email address",
            Map.of("oldEmail", String.valueOf(oldEmail), "newEmail", String.valueOf(newEmail),
                "passwordMatch", passwordMatch, "emailMatch", emailMatch),
            SecurityError.EMAIL_OR_PW_MISMATCH);
    }
}
