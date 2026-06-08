package com.softropic.skillars.infrastructure.message;

public record Failure(String helpCode, String msgKey, String msg) implements Response {
}
