package com.softropic.skillars.platform.security.contract.event;

/**
 * Someone tried to register (parent, player or coach) with an email address or phone number that an
 * existing account already holds. The registration services answer that request exactly like a
 * successful registration (no enumeration oracle) and publish this event instead, so the existing
 * account's owner is told by email ({@code EmailTemplate.CREATION_DUP}).
 *
 * <p>Every field describes the EXISTING account, never the registration request.
 */
public record RegistrationDuplicateAttemptEvent(
    Long existingUserId, String toAddress, String firstName, String lastName, String langKey) {}
