package com.softropic.skillars.platform.notification.infrastructure.listener;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.infrastructure.web.FrontendLinkBuilder;
import com.softropic.skillars.platform.notification.contract.EmailTemplate;
import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.event.AccountChangeEvent;
import com.softropic.skillars.platform.security.contract.event.AccountChangeUserInfo;
import com.softropic.skillars.platform.security.contract.util.ShortCode;

import lombok.extern.slf4j.Slf4j;

import static net.logstash.logback.argument.StructuredArguments.kv;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class AccountChangeEmailListener {

    private final ApplicationEventPublisher publisher;
    private final FrontendLinkBuilder frontendLinks;

    public AccountChangeEmailListener(ApplicationEventPublisher publisher,
                                       FrontendLinkBuilder frontendLinks) {
        this.publisher = publisher;
        this.frontendLinks = frontendLinks;
    }

    @Transactional
    @EventListener
    public void handleAccountChange(AccountChangeEvent event) {
        log.info("Sending account change notification email", kv("action", event.getAction()));
        sendNotificationEmail(event);
    }

    private void sendNotificationEmail(AccountChangeEvent event) {
        final String helpCode = ShortCode.shortenInt(UUID.randomUUID().hashCode());
        // Frontend origin (not the backend's ${baseurl}:${server.port}) — any link a template builds
        // from it must append '/#/<route>' (hash router).
        final String fullBaseUrl = frontendLinks.base();

        final Map<String, Object> data = new HashMap<>();
        data.put("helpCode", helpCode);
        data.put("baseUrl", fullBaseUrl);
        data.put("action", event.getAction().name());
        data.put("oldValue", event.getOldValue() != null ? event.getOldValue() : "");
        data.put("newValue", event.getNewValue() != null ? event.getNewValue() : "");

        final AccountChangeUserInfo userInfo = event.getUserInfo();
        final Recipient recipient = new Recipient();
        recipient.setEmail(userInfo.email());
        recipient.setFirstname(userInfo.firstname());
        recipient.setLastname(userInfo.lastname());
        recipient.setLangKey(userInfo.langKey());
        recipient.setTitle(userInfo.title());
        recipient.setGender(userInfo.gender());

        final Envelope envelope = new Envelope(
                List.of(recipient),
                EmailTemplate.PROFILE_CHANGE,
                Instant.now(ClockProvider.getClock()).plus(Duration.ofDays(7)),
                data,
                helpCode
        );

        publisher.publishEvent(envelope);
    }
}
