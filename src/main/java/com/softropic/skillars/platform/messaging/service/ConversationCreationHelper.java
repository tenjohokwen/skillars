package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.platform.messaging.repo.Conversation;
import com.softropic.skillars.platform.messaging.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class ConversationCreationHelper {

    private final ConversationRepository conversationRepository;

    /**
     * Runs in its own transaction (REQUIRES_NEW). If a concurrent insert triggers the unique
     * constraint, DataIntegrityViolationException escapes cleanly, Spring rolls back this
     * sub-transaction, and the caller can re-query in its own clean transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Conversation tryCreate(UUID coachId, Long playerId, Long parentId) {
        return conversationRepository.findByCoachIdAndPlayerId(coachId, playerId)
            .orElseGet(() -> {
                var c = new Conversation();
                c.setCoachId(coachId);
                c.setPlayerId(playerId);
                c.setParentId(parentId);
                c.setCreatedAt(Instant.now());
                return conversationRepository.saveAndFlush(c);
            });
    }
}
