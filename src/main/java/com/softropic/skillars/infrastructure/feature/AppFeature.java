package com.softropic.skillars.infrastructure.feature;

public enum AppFeature {
    ARACHNID_ENABLED("arachnid-enabled"),
    VIDEOINTEL_ENABLED("videointel-enabled");

    private final String key;

    AppFeature(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
