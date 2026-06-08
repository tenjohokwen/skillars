package com.softropic.skillars.platform.security.service;

public interface LoginAttemptConsumer<T> {
    void loginSucceeded(T identifier);
    void loginFailed(T identifier);
}
