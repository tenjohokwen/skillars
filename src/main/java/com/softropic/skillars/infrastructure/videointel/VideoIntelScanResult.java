package com.softropic.skillars.infrastructure.videointel;

// confidence is nullable: null for PASSED results (no meaningful score), non-null for FLAGGED results only
public record VideoIntelScanResult(boolean flagged, Double confidence, String description) {}
