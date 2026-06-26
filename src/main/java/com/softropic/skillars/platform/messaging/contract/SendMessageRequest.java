package com.softropic.skillars.platform.messaging.contract;

// No @NotBlank/@Size — validation done in service to produce messaging.invalidContent (400) via MessagingApiAdvice
public record SendMessageRequest(
    String content
) {}
