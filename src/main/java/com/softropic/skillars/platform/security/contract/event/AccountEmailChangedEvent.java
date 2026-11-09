package com.softropic.skillars.platform.security.contract.event;

/**
 * skillars-deferred-175 AC4 (RS03c): the account's email changed (a confirmed change or its undo). Published inside the
 * email-change transaction; carries only the user id, so a listener re-reads the current address rather than trusting one
 * that a later undo may already have replaced.
 */
public record AccountEmailChangedEvent(Long userId) {}
