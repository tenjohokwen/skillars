package com.softropic.skillars.platform.session.contract;

public record SessionDnaScore(
    int technical,
    int physical,
    int cognitive,
    int matchRealism,
    int weakFootFocus
) {}
