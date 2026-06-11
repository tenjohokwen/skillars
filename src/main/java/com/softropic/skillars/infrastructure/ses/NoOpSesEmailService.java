package com.softropic.skillars.infrastructure.ses;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.ses.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpSesEmailService implements SesEmailService {

    @Override
    public void send(String toAddress, String subject, String htmlBody) {
        log.info("NoOp SES: email suppressed — subject={}", subject);
    }
}
