package com.softropic.skillars.platform.security.contract.event;

/**
 * Someone registered (parent, player or coach) with a NEW email address and a phone number an existing
 * account already holds. Phone numbers are not verified at registration, so the number may sit on
 * someone else's account; the registrant is told at the address they typed
 * ({@code EmailTemplate.REGISTRATION_PHONE_IN_USE}), next to the {@link RegistrationDuplicateAttemptEvent}
 * that tells the existing account.
 *
 * <p>Every field comes from the registration REQUEST. The typed first name is deliberately not carried:
 * the mail goes to an address nobody has verified, so it must not echo text the registrant chose.
 *
 * @param toAddress the email address the registrant typed
 * @param langKey   the request's language, {@code "en"} when it was blank
 * @param phoneHolderUserId the account holding the number, logged with the mail's help code (never
 *                  put in the mail) so support can trace a "this number is mine" contact
 */
public record RegistrationPhoneInUseEvent(String toAddress, String langKey, Long phoneHolderUserId) {}
