package com.softropic.skillars.platform.messaging.service;

import com.softropic.skillars.infrastructure.gemini.GeminiClient;
import com.softropic.skillars.platform.messaging.contract.ModerationFailureEvent;
import com.softropic.skillars.platform.messaging.contract.ModerationResult;
import com.softropic.skillars.platform.messaging.contract.ModerationService;
import com.softropic.skillars.platform.messaging.contract.ModerationVerdict;
import com.softropic.skillars.platform.messaging.repo.Message;
import com.softropic.skillars.platform.messaging.repo.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiModerationService implements ModerationService {

    private static final String USER_CONTENT_BEGIN_DELIMITER = "---BEGIN USER CONTENT---";
    private static final String USER_CONTENT_END_DELIMITER = "---END USER CONTENT---";

    private final GeminiClient geminiClient;
    private final ModerationResultApplier moderationResultApplier;
    private final MessageRepository messageRepository;
    private final ApplicationEventPublisher publisher;

    @Value("${platform.messaging.moderation.gemini.prompt-template}")
    private String promptTemplate;

    @Value("${platform.messaging.moderation.gemini.max-input-chars:4000}")
    private int maxInputChars;

    @Override
    public ModerationResult moderate(Long messageId, String content) {
        if (content == null || content.isBlank()) {
            return moderationResultApplier.applyResult(messageId, ModerationVerdict.SAFE);
        }

        int cutoff = maxInputChars;
        if (content.length() > cutoff) {
            // A char-count cutoff can land between a surrogate pair's two halves — the same class of
            // bug AdminQueueService.preview() already fixes for its own content-preview truncation
            // (deferred-work.md, "code review of skillars-deferred-16" D5). Back off while the
            // boundary sits on a high surrogate so the emitted prompt never ends in one; a `while`,
            // not a single `if`, because malformed content can carry two or more consecutive unpaired
            // high surrogates (e.g. a crafted `\uD800\uD800` JSON payload — codePointCount() counts
            // each as its own code point, so MessagingService.sendMessage's length guard doesn't
            // reject it), and a single backoff would still leave the truncated prompt ending in one.
            // Bounds-safe: cutoff starts below content.length() (just checked) and only decreases, so
            // cutoff - 1 is always a valid index into content.
            while (cutoff > 0 && Character.isHighSurrogate(content.charAt(cutoff - 1))) {
                cutoff--;
            }
        }
        String input = content.length() > maxInputChars
            ? content.substring(0, cutoff)
            : content;
        if (input.length() < content.length()) {
            log.debug("Truncated content for Gemini: messageId={}, originalLen={}, truncatedLen={}",
                messageId, content.length(), input.length());
        }

        ModerationVerdict verdict;
        String failureReason = null;
        try {
            // TODO: replace this delimiter convention with real structural separation once
            // GeminiClientImpl/GeminiApiResponse support a systemInstruction + multi-turn role field.
            String sanitizedInput = input
                .replace(USER_CONTENT_BEGIN_DELIMITER, "")
                .replace(USER_CONTENT_END_DELIMITER, "");
            String prompt = promptTemplate
                + "\n\n" + USER_CONTENT_BEGIN_DELIMITER + "\n"
                + sanitizedInput
                + "\n" + USER_CONTENT_END_DELIMITER;
            verdict = geminiClient.evaluate(prompt);
        } catch (Exception e) {
            verdict = ModerationVerdict.UNCERTAIN;
            failureReason = e.getMessage();
        }

        ModerationResult result = moderationResultApplier.applyResult(messageId, verdict);

        if (failureReason != null) {
            Long conversationId = messageRepository.findById(messageId)
                .map(Message::getConversationId).orElse(null);
            log.warn("Gemini moderation failed: messageId={}, conversationId={}, reason={}",
                messageId, conversationId, failureReason);
            publisher.publishEvent(new ModerationFailureEvent(messageId, conversationId, failureReason));
        }

        return result;
    }
}
