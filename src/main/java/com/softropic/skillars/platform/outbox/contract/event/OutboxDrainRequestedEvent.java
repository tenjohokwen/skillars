package com.softropic.skillars.platform.outbox.contract.event;

/**
 * skillars-deferred-91 AC1: published (inside the producing business transaction, via
 * {@code OutboxService.requestDrainAfterCommit()}) when one or more rows have been written to
 * {@code main.outbox_messages}. {@code OutboxService} listens for it with
 * {@code @TransactionalEventListener(AFTER_COMMIT)} and drains the table off the request path, so
 * exactly one drain fires after the transaction commits.
 */
public record OutboxDrainRequestedEvent() {
}
