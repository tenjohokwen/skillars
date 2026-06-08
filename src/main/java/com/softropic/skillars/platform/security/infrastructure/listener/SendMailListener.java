package com.softropic.skillars.platform.security.infrastructure.listener;



import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.security.contract.event.SendMailEvent;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.service.UserService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static net.logstash.logback.argument.StructuredArguments.entries;

@Component
public class SendMailListener {
    private static final Logger                    LOGGER = LoggerFactory.getLogger(SendMailListener.class);

    private final ApplicationEventPublisher publisher;
    private final UserService               userService;

    public SendMailListener(ApplicationEventPublisher publisher, UserService userService) {
        this.publisher = publisher;
        this.userService = userService;
    }

    @Transactional
    @EventListener
    public void handleSendMailEvent(SendMailEvent sendMailEvent) {
        final List<User> users = userService.findUsersByIds(sendMailEvent.userIds());
        if(!Objects.equals(users.size(), sendMailEvent.userIds().size())) {
            final HashSet<Long> targetIds = new HashSet<>(sendMailEvent.userIds());
            final Set<Long> actualIds = users.stream().map(User::getId).collect(Collectors.toSet());
            targetIds.removeAll(actualIds);
            final String msg = "Could not send mail to all users because not all ids fetched users.";
            LOGGER.warn(msg, entries(Map.of("missing ids", targetIds)));
        }
        final List<Recipient> recipients = users.stream().map(this::buildRecipient).toList();
        final Envelope envelope = new Envelope(recipients,
                                               sendMailEvent.emailTemplate(),
                                               sendMailEvent.deadline(),
                                               sendMailEvent.data(),
                                               sendMailEvent.sendId());
        publisher.publishEvent(envelope);
    }

    private Recipient buildRecipient(final User user) {
        final Recipient recipient = new Recipient();
        recipient.setFirstname(user.getFirstName());
        recipient.setLastname(user.getLastName());
        recipient.setEmail(user.getEmail());
        recipient.setLangKey(user.getLangKey());
        recipient.setTitle(user.getTitle());
        recipient.setGender(Objects.toString(user.getGender()));
        return recipient;
    }

}
