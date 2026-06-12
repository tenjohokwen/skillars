package com.softropic.skillars.platform.security.contract;

public record MessagingPolicy(boolean canMessage, boolean parentVisible, boolean directAllowed) {

    public static MessagingPolicy prohibited() {
        return new MessagingPolicy(false, true, false);
    }

    public static MessagingPolicy parentManaged() {
        return new MessagingPolicy(true, true, false);
    }

    public static MessagingPolicy supervised() {
        return new MessagingPolicy(true, true, true);
    }

    public static MessagingPolicy unrestricted() {
        return new MessagingPolicy(true, false, true);
    }
}
