package com.softropic.skillars.platform.security.audit.listener;

import com.softropic.skillars.infrastructure.util.ClockProvider;
import com.softropic.skillars.platform.security.audit.api.AuditTrail;
import com.softropic.skillars.platform.security.audit.service.TrailService;
import com.softropic.skillars.platform.security.contract.event.AccountChangeEvent;
import com.softropic.skillars.infrastructure.security.RequestMetadata;
import com.softropic.skillars.infrastructure.security.RequestMetadataProvider;
import com.softropic.skillars.platform.security.contract.util.ShortCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class AccountChangeEventListener {

    private final TrailService trailService;

    public AccountChangeEventListener(TrailService trailService) {
        this.trailService = trailService;
    }

    @EventListener
    public void handleAccountChange(AccountChangeEvent event) {
        log.info("Account change event",
            kv("operation", "account_change"),
            kv("eventType", event.getClass().getSimpleName()),
            kv("status", "RECEIVED"));
        recordAuditTrail(event);
    }

    private void recordAuditTrail(AccountChangeEvent event) {
        final AuditTrail auditTrail = new AuditTrail();
        final String logId = ShortCode.shortenInt(UUID.randomUUID().hashCode());
        boolean logged = false;
        try {
            final RequestMetadata clientInfo = RequestMetadataProvider.getClientInfo();

            auditTrail.setEventTimestamp(ClockProvider.getClock().instant());
            auditTrail.setMsg("PROFILE_CHANGE: %s".formatted(event.getAction()));
            auditTrail.setLogin(event.getUserInfo().email());
            // skillars-deferred-172: an email change is confirmed and undone from links in mails, without a
            // session; userInfo.email is the address the change was made from (confirm) or restored (undo).
            auditTrail.setAuthenticated(!isFromEmailLink(event));
            auditTrail.setRelevantProperties(buildRelevantProperties(event));
            auditTrail.setUserAgent(clientInfo.getUserAgent());
            auditTrail.setIpAddress(clientInfo.getIpAddress());
            auditTrail.setClientId(clientInfo.getClientIdentifier());
            auditTrail.setBrowserCookie(clientInfo.getBrowserCookie());
            auditTrail.setUrl(clientInfo.getReqUrl());
            auditTrail.setLogId(logId);
            auditTrail.setSessionId(clientInfo.getSessionId());
            auditTrail.setRequestId(clientInfo.getRequestId()); //setting this value so that it shows in logs. If not set here it will not show in logs but will show in the DB because of the pre-persist event listener
            trailService.recordTrail(auditTrail);
        }
        catch (Exception e) {
            log.error("Account change audit trail: could not save",
                kv("operation", "account_change"),
                kv("status", "DB_ERROR"),
                e);
            logged = true;
        }
        finally {
            if (!logged) {
                log.info("Account change audit trail recorded",
                    kv("operation", "account_change"),
                    kv("status", "RECORDED"));
            }
        }
    }

    private static boolean isFromEmailLink(AccountChangeEvent event) {
        return event.getAction() == AccountChangeEvent.Action.EMAIL_CHANGED
            || event.getAction() == AccountChangeEvent.Action.EMAIL_CHANGE_UNDONE;
    }

    private Map<String, Object> buildRelevantProperties(AccountChangeEvent event) {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("action", event.getAction());
        properties.put("oldValue", event.getOldValue() != null ? event.getOldValue() : "");
        properties.put("newValue", event.getNewValue() != null ? event.getNewValue() : "");
        return properties;
    }
}
