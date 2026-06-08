package com.softropic.skillars.infrastructure.feature;

public enum AppFeature {
    PAYMENTS("payments"),
    INVOICING("invoicing");

    private final String key;

    AppFeature(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
