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

import java.util.regex.Pattern;

@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiModerationService implements ModerationService {

    private static final String USER_CONTENT_BEGIN_DELIMITER = "---BEGIN USER CONTENT---";
    private static final String USER_CONTENT_END_DELIMITER = "---END USER CONTENT---";

    /**
     * Anything that looks like it is trying to speak to the prompt rather than to the recipient: our
     * own delimiters (matched loosely — any dash count, any whitespace, any case, zero-width padding
     * removed first) and the usual instruction-override phrasing. Such content is never sent to the
     * model for a verdict it could be talked into; it is held for a human instead.
     */
    private static final Pattern INJECTION_MARKERS = Pattern.compile(
        "(?i)(?:(?:begin|end)\\s+user\\s+content"
            + "|user\\s+content\\s*-{2,}"
            + "|-{3,}\\s*(?:begin|end)\\b"
            + "|ignore\\s+(?:all\\s+|any\\s+|the\\s+)?(?:previous|prior|above|earlier)\\s+(?:instructions|prompts?|rules)"
            + "|disregard\\s+(?:all\\s+|any\\s+|the\\s+)?(?:previous|prior|above|earlier)"
            + "|(?:^|\\n)\\s*(?:system|assistant|developer)\\s*:"
            + "|respond\\s+(?:only\\s+)?with\\s+safe|mark\\s+(?:this\\s+|everything\\s+|it\\s+)?(?:as\\s+)?safe)");

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

        if (looksLikePromptInjection(content)) {
            // Checked on the FULL content, before truncation: a marker placed past the cutoff is
            // harmless to the prompt but a marker split by the cutoff must not slip through either.
            // Not a Gemini failure, so no ModerationFailureEvent; UNCERTAIN holds the message
            // (UNDER_REVIEW + admin alert) exactly as an unresolved verdict does.
            log.warn("Moderation input contains prompt-injection markers, holding without a model call: "
                + "messageId={}", messageId);
            return moderationResultApplier.applyResult(messageId, ModerationVerdict.UNCERTAIN);
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
            // GeminiClient.evaluate takes a single combined prompt (no systemInstruction / role split),
            // so the delimiters remain the only structure. They cannot be reassembled out of user
            // content because any content containing them (in any spacing/case/dash form, see
            // INJECTION_MARKERS) was already diverted above and never reaches this point.
            String prompt = promptTemplate
                + "\n\n" + USER_CONTENT_BEGIN_DELIMITER + "\n"
                + input
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

    static boolean looksLikePromptInjection(String content) {
        // Zero-width / format characters and soft hyphens are stripped so "---BEGIN\u200B USER..." cannot
        // dodge the match; checked in a loop-free way because nothing is rewritten, only detected.
        String normalised = content.replaceAll("[\\p{Cf}]", "");
        return INJECTION_MARKERS.matcher(normalised).find();
    }
}
