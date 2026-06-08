package com.softropic.skillars.platform.notification.service;

import com.softropic.skillars.platform.notification.contract.Envelope;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntity;
import com.softropic.skillars.platform.notification.repo.EnvelopeEntityRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ResendEmailService {

    private final EnvelopeEntityRepository  envelopeEntityRepository;
    private final ApplicationEventPublisher publisher;

    public void resendEmail(String sendId) {
        EnvelopeEntity envelopeEntity = envelopeEntityRepository.findBySendId(sendId);
        final Envelope envelope = EnvelopeMapper.toEnvelope(envelopeEntity);
        publisher.publishEvent(envelope);
    }
}
