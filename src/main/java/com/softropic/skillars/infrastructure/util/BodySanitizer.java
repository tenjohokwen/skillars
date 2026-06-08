package com.softropic.skillars.infrastructure.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Utility to sanitize request/response bodies for logging.
 */
@Slf4j
public class BodySanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "newPassword", "oldPassword", "currentPassword",
            "otp", "otpCode", "verificationCode", "activationKey", "resetKey",
            "token", "accessToken", "refreshToken", "jwt",
            "cvv", "cvc", "pin", "apiKey",
            "msisdn", "merchant_key", "merchantKey"
    );

    private static final String REDACTED_VALUE = "[REDACTED]";
    private static final int MAX_LOG_LENGTH = 10000;

    /**
     * Sanitizes the given content if it's JSON, otherwise returns a truncated version.
     */
    public static String sanitize(byte[] content, String contentType) {
        if (content == null || content.length == 0) {
            return "";
        }

        String rawBody = new String(content, StandardCharsets.UTF_8);

        if (contentType != null && contentType.contains("application/json")) {
            try {
                JsonNode node = MAPPER.readTree(rawBody);
                sanitizeNode(node);
                String sanitized = MAPPER.writeValueAsString(node);
                return truncateIfNeeded(sanitized);
            } catch (Exception e) {
                // If it's not valid JSON despite the content type, just return truncated raw body
                log.warn("Failed to parse body as JSON for sanitization",
                        kv("operation", "body_sanitization"),
                        kv("status", "PARSE_ERROR"));
            }
        }

        return truncateIfNeeded(rawBody);
    }

    private static void sanitizeNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                if (isSensitive(key)) {
                    objectNode.set(key, new TextNode(REDACTED_VALUE));
                } else {
                    sanitizeNode(field.getValue());
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                sanitizeNode(arrayNode.get(i));
            }
        }
    }

    private static boolean isSensitive(String key) {
        if (key == null) return false;
        String lowerKey = key.toLowerCase();
        return SENSITIVE_KEYS.stream().anyMatch(s -> lowerKey.contains(s.toLowerCase()));
    }

    private static String truncateIfNeeded(String body) {
        if (body.length() > MAX_LOG_LENGTH) {
            return body.substring(0, MAX_LOG_LENGTH) + "... [TRUNCATED]";
        }
        return body;
    }
}
