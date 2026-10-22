package com.softropic.skillars.platform.booking.contract;

import java.util.List;

/** skillars-12-7 AC8: {@code count} is the true total; {@code items} is capped, most recent first. */
public record MissingRecapsResponse(int count, List<MissingRecapItem> items) {}
