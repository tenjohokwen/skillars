package com.softropic.skillars.platform.messaging.contract;

/**
 * Published after the retention scheduler hard-deletes message rows, so consumers can clean up
 * references to messages that no longer exist.
 *
 * <p>Messaging publishes, admin listens — the same direction as {@link MessageHeldForReviewEvent}.
 * {@code platform.admin} already imports {@code platform.messaging.contract}; messaging must not
 * gain an import of {@code platform.admin}, which is why the alert cleanup this triggers cannot
 * simply be called inline from {@code MessageRetentionScheduler}.
 *
 * @param deletedCount number of message rows removed by the run that published this event
 */
public record MessagesPurgedEvent(int deletedCount) {}
