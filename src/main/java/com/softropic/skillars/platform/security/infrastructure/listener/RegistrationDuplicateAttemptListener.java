package com.softropic.skillars.platform.security.infrastructure.listener;

import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.service.NotificationOutboxSupport;
import com.softropic.skillars.platform.security.contract.event.RegistrationDuplicateAttemptEvent;
import com.softropic.skillars.platform.security.contract.event.RegistrationPhoneInUseEvent;
import com.softropic.skillars.platform.security.contract.util.ShortCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Sends the existing account's owner the {@link EmailTemplate#CREATION_DUP} notice when someone tries
 * to register (parent, player or coach) with that account's email address or phone number. The
 * registration services answer such a request exactly like a successful registration, so this email
 * is the only place the collision is revealed, and only to the address that already owns it.
 *
 * <p>Same transactional-outbox shape as {@link ParentRegistrationEmailListener}: enqueued BEFORE_COMMIT
 * in the registration transaction, so the notice exists if and only if that transaction commits. The
 * template data matches what {@code EmailRegistrationStrategy.notifyUserExists} puts in an envelope
 * ({@code baseUrl} = the frontend origin, {@code helpCode} = a short support code). That method is not
 * reused because it is {@code @RateLimited} per IP: a 429 only on the duplicate path would itself tell
 * a caller the address is taken.
 *
 * <p>Also sends the {@link EmailTemplate#REGISTRATION_PHONE_IN_USE} mail to the address the registrant
 * typed when only the phone number matched ({@link RegistrationPhoneInUseEvent}), same shape and data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationDuplicateAttemptListener {

    private final NotificationOutboxSupport notificationOutboxSupport;
    private final FrontendLinkBuilder frontendLinks;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDuplicateAttempt(RegistrationDuplicateAttemptEvent event) {
        if (event.toAddress() == null || event.toAddress().isBlank()) {
            log.warn("Cannot send duplicate-registration notice: address is blank",
                kv("userId", event.existingUserId()));
            return;
        }
        String sendId = UUID.randomUUID().toString();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("baseUrl", frontendLinks.base());
            data.put("helpCode", ShortCode.shortenInt(UUID.randomUUID().hashCode()));

            Recipient recipient = new Recipient();
            recipient.setEmail(event.toAddress());
            recipient.setLangKey(event.langKey());
            recipient.setFirstname(event.firstName());
            recipient.setLastname(event.lastName());

            notificationOutboxSupport.enqueueEmail(EmailTemplate.CREATION_DUP, recipient, data, sendId);
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.CREATION_DUP),
                kv("sendId", sendId), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPhoneInUse(RegistrationPhoneInUseEvent event) {
        if (event.toAddress() == null || event.toAddress().isBlank()) {
            log.warn("Cannot send phone-in-use registration notice: address is blank");
            return;
        }
        String sendId = UUID.randomUUID().toString();
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("baseUrl", frontendLinks.base());
            String helpCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());
            data.put("helpCode", helpCode);
            // The mail tells someone whose number sits on another account to quote this code; support
            // finds the holder from this line.
            log.info("[REGISTRATION_PHONE_IN_USE_NOTICE] phone-in-use notice queued",
                kv("helpCode", helpCode), kv("phoneHolderUserId", event.phoneHolderUserId()), kv("sendId", sendId));

            // No first name: the registrant has no account, and the typed name is not echoed into a mail
            // to an address nobody has verified (the template greets without one).
            Recipient recipient = new Recipient();
            recipient.setEmail(event.toAddress());
            recipient.setLangKey(event.langKey());

            notificationOutboxSupport.enqueueEmail(EmailTemplate.REGISTRATION_PHONE_IN_USE, recipient, data, sendId);
        } catch (Exception e) {
            log.error("Failed to prepare/publish notification", kv("template", EmailTemplate.REGISTRATION_PHONE_IN_USE),
                kv("sendId", sendId), e);
        }
    }
}
