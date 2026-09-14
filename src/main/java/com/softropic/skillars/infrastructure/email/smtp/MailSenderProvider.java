package com.softropic.skillars.infrastructure.email.smtp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Story ses-1.2 AC2: moved unchanged in logic from
 * {@code platform.notification.infrastructure.MailSenderProvider}, now constructed from
 * {@link SmtpProperties} instead of the old {@code EmailProperties}. No longer implements the
 * deleted {@code platform.notification.service.SenderProvider} interface — {@link SmtpEmailSender}
 * calls {@link #nextSender()} on this class directly, per §3.3's ownership table ("pick an SMTP
 * provider" moves to {@code infrastructure.email.smtp} as a concrete call, not a re-abstracted
 * interface).
 *
 * <p>skillars-deferred-110 AC3: gated on {@code app.email.transport=smtp} — matching {@link
 * SmtpEmailSender}, its one and only consumer — so a malformed {@code provider-configs} entry (a
 * non-numeric port, for instance) can never crash a {@code transport=ses} prod boot out of a bean
 * that would otherwise be constructed unconditionally in every profile. Validation runs inside
 * this constructor itself, not a separate {@code @PostConstruct} validator, because bean-creation
 * ordering between a sibling {@code @PostConstruct} and this constructor is not guaranteed —
 * only a check performed here is guaranteed to win the race and produce a named, friendly {@code
 * AppSetupException} instead of a raw {@code NumberFormatException}/{@code
 * ArithmeticException}/{@code IndexOutOfBoundsException}. The check itself lives in {@link
 * ProviderConfigsValidator}, shared with {@link SmtpPropertiesValidator} (code review 2026-09-14 —
 * the two had drifted out of sync byte-for-byte before the extraction).
 */
@Component
@ConditionalOnProperty(name = "app.email.transport", havingValue = "smtp")
public class MailSenderProvider {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final List<JavaMailSenderImpl> providers;

    public MailSenderProvider(SmtpProperties smtpProperties) {
        List<ProviderConfig> providerConfigs = smtpProperties.getProviderConfigs();
        ProviderConfigsValidator.validate(providerConfigs);
        this.providers = providerConfigs.stream()
                .map(this::toMailSender)
                .collect(Collectors.toList());
    }

    /**
     * skillars-ses-1.2 code review 2026-09-11, owner decision (a): {@code mail.debug} is deliberately
     * NOT set. JavaMail's debug stream is {@code System.out} and logs the full SMTP dialogue,
     * including the {@code AUTH PLAIN base64(user\0user\0password)} line — a real credential leak to
     * stdout/container logs on every send, newly reachable once dev/uat run this transport for real
     * (AC6). {@code project-context.md}'s "never log secrets" rule wins over AC2's "unchanged in
     * logic": the pre-story class had the same leak, but it never sent real mail (dev ran {@code
     * transport=log}), so this is the first commit where it becomes live.
     *
     * <p>Timeouts (connect/read/write, 5s each — matching {@link SmtpHealthIndicator}'s own probe
     * budget) are new: JavaMail defaults every one of these to infinite. The three registration
     * listeners call {@link com.softropic.skillars.infrastructure.email.OutboundEmailSender} directly
     * from an {@code @TransactionalEventListener(AFTER_COMMIT)} with no {@code @Async}, so a
     * blackholed/unreachable SMTP host would otherwise hang the calling request thread indefinitely
     * (code review 2026-09-11).
     */
    private JavaMailSenderImpl toMailSender(final ProviderConfig providerConfig) {
        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setDefaultEncoding(String.valueOf(StandardCharsets.UTF_8));
        javaMailSender.setHost(providerConfig.getHost());
        javaMailSender.setPort(Integer.parseInt(providerConfig.getPort()));
        javaMailSender.setPassword(providerConfig.getPassword());
        javaMailSender.setUsername(providerConfig.getUsername());
        javaMailSender.setProtocol("smtp");
        Properties props = javaMailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        props.put("mail.smtp.writetimeout", "5000");
        return javaMailSender;
    }

    /**
     * skillars-deferred-110 AC3: {@code Math.floorMod} (not {@code %}) so an {@link AtomicInteger}
     * rollover to {@code Integer.MIN_VALUE} after 2^31 calls can never index negative. It still
     * divides by zero on an empty provider list exactly like {@code %} does — the constructor's
     * own empty-list guard is what actually prevents that case from reaching here.
     */
    public JavaMailSenderImpl nextSender() {
        final var nextProviderPos = Math.floorMod(counter.getAndIncrement(), providers.size());
        return providers.get(nextProviderPos);
    }
}
