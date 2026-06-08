package com.softropic.skillars.infrastructure.message;

import java.util.Map;

public record Success(String helpCode, String msgKey, String msg, Map<String, Object> payload) implements Response {
}
