package com.softropic.skillars.platform.booking.contract;

/**
 * What deleting or changing one availability window would leave behind: the coach's upcoming
 * sessions (requested or booked) that start inside the window today and would no longer be inside
 * it. They stay booked either way; the coach is told before confirming (owner decision 2026-11-06).
 */
public record WindowImpactResponse(int sessionsLeftOutside) {}
