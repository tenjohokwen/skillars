package com.softropic.skillars.platform.notification.infrastructure;

import com.softropic.skillars.platform.notification.contract.EmailProperties;
import com.softropic.skillars.platform.notification.contract.ProviderConfig;
import com.softropic.skillars.platform.notification.service.SenderProvider;

import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class MailSenderProvider implements SenderProvider {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final List<JavaMailSenderImpl> providers;

    public MailSenderProvider(EmailProperties emailProperties) {
        this.providers = emailProperties.getProviderConfigs().stream()
                .map(this::toMailSender)
                .collect(Collectors.toList());
    }

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
        props.put("mail.debug", "true");
        return javaMailSender;
    }

    public JavaMailSenderImpl nextSender() {
        final var currentCounterValue = counter.getAndIncrement();
        final var nextProviderPos = currentCounterValue % providers.size();
        return providers.get(nextProviderPos);
    }

    void resetProviderRoundRobin() {
        counter.set(0);
    }
}
