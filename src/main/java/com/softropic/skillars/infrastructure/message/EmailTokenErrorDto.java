package com.softropic.skillars.infrastructure.message;

public record EmailTokenErrorDto(String helpCode, ErrorMsg errorMsg, boolean canResend) {}
