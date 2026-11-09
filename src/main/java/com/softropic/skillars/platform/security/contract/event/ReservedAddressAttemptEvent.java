package com.softropic.skillars.platform.security.contract.event;

/**
 * Someone tried to register with, or move another account onto, an address that is reserved: the old address of
 * an email change still inside its undo window (skillars-deferred-172). The request is answered like a success;
 * the reserving account is told at {@code toAddress}, the reserved address itself, never at its current email.
 */
public record ReservedAddressAttemptEvent(
    Long reservingUserId, String toAddress, String firstName, String lastName, String langKey) {}
