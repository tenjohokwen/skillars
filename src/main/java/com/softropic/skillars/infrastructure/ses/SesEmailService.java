package com.softropic.skillars.infrastructure.ses;

public interface SesEmailService {

    void send(String toAddress, String subject, String htmlBody);
}
