package com.softropic.skillars.platform.admin.contract;

/**
 * Who an admin-outcome notification (suspension, reinstatement, warning, dispute outcome, GDPR export
 * ready, review moderation outcome) goes to, resolved by the publishing admin service inside its own
 * transaction and carried on the event. The notification listener holds no repository: everything it
 * needs to build the email is on the event (see {@code NotificationOutboxSupport}'s "Payload construction
 * should still stay defensive").
 *
 * <p>Always a {@code main."user"} owner of the email address. For a minor player this is the linked
 * parent, never the child (see {@code AdminNotificationRecipientResolver}).
 *
 * @param email   the recipient's own address, never blank
 * @param langKey the recipient's language key, defaulted to {@code "en"}
 */
public record AdminNotificationRecipient(String email, String langKey) {
}
