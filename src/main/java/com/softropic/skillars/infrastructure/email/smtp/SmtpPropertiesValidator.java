package com.softropic.skillars.infrastructure.email.smtp;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * skillars-deferred-110 AC3: the SMTP-side sibling of {@link
 * com.softropic.skillars.infrastructure.ses.SesPropertiesValidator}, mirroring its shape
 * (dedicated {@code @Component}, {@link ConditionalOnProperty} on {@code
 * app.email.transport=smtp}, {@link PostConstruct}).
 *
 * <p><strong>This is redundant defense, not the load-bearing check.</strong> Bean-creation
 * ordering between this class's {@code @PostConstruct} and {@link MailSenderProvider}'s own
 * constructor is not guaranteed by Spring, so a malformed {@code provider-configs} entry could
 * still reach {@link MailSenderProvider}'s constructor first even if this validator would
 * eventually have caught it too. {@link MailSenderProvider} therefore performs the same checks
 * itself, unconditionally, in its own constructor — that is what actually guarantees the friendly
 * {@code AppSetupException} always wins the race. This class exists as a matching, discoverable
 * sibling to {@code SesPropertiesValidator}, not as the primary guard.
 *
 * <p>Code review 2026-09-14: the checks themselves live in {@link ProviderConfigsValidator}, shared
 * with {@link MailSenderProvider} — this class and that one's constructor had drifted out of sync
 * byte-for-byte before the extraction (a blank {@code password} was accepted by neither; a
 * syntactically-numeric but out-of-range port was rejected by neither).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.email.transport", havingValue = "smtp")
public class SmtpPropertiesValidator {

    private final SmtpProperties props;

    @PostConstruct
    void validate() {
        ProviderConfigsValidator.validate(props.getProviderConfigs());
    }
}
