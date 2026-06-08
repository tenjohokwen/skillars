package com.softropic.skillars.platform.notification.service;

import org.springframework.mail.javamail.JavaMailSenderImpl;

public interface SenderProvider {
    JavaMailSenderImpl nextSender();
}
