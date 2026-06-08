package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.contract.Recipient;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.RecipientEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


public class EnvelopeMapper {
    private EnvelopeMapper() {}

    public static EnvelopeEntity toEntity(final Envelope envelope) {
        final EnvelopeEntity envelopeEntity = new EnvelopeEntity();
        envelopeEntity.setEmailTemplate(envelope.emailTemplate());
        envelopeEntity.setDeadline(envelope.deadline());
        envelopeEntity.setData(envelope.data());
        envelopeEntity.setRecipients(toRecipientEntities(envelope.recipients()));
        envelopeEntity.setId(UUID.randomUUID());
        envelopeEntity.setSendId(envelope.sendId());
        return envelopeEntity;
    }

    public static List<RecipientEntity> toRecipientEntities(final List<Recipient> recipients) {
        final List<RecipientEntity> entities = new ArrayList<>();
        recipients.forEach(recipient -> entities.add(toRecipientEntity(recipient)));
        return entities;
    }

    public static RecipientEntity toRecipientEntity(final Recipient recipient) {
        final RecipientEntity recipientEntity = new RecipientEntity();
        recipientEntity.setEmail(recipient.getEmail());
        recipientEntity.setFirstname(recipient.getFirstname());
        recipientEntity.setLastname(recipient.getLastname());
        recipientEntity.setTitle(recipient.getTitle());
        recipientEntity.setLangKey(recipient.getLangKey());
        recipientEntity.setGender(recipient.getGender());
        return recipientEntity;
    }

    public static Envelope toEnvelope(final EnvelopeEntity entity) {
        Map<String, Object> data = new HashMap<>(entity.getData());
        final List<Recipient> recipients = new ArrayList<>();
        entity.getRecipients().forEach(recipient -> recipients.add(toRecipient(recipient)));
        return new Envelope(recipients,
                            entity.getEmailTemplate(),
                            entity.getDeadline(),
                            data,
                            entity.getSendId());
    }

    private static Recipient toRecipient(final RecipientEntity entity) {
        final Recipient recipient = new Recipient();
        recipient.setEmail(entity.getEmail());
        recipient.setGender(entity.getGender());
        recipient.setFirstname(entity.getFirstname());
        recipient.setLastname(entity.getLastname());
        recipient.setTitle(entity.getTitle());
        recipient.setLangKey(entity.getLangKey());
        return recipient;
    }
}
