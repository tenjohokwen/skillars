package com.softropic.skillars.platform.notification.contract;

/**
 * User-facing notification categories (notification centre, UI-gap chunk C9). Every
 * {@link EmailTemplate} maps to exactly one category in {@code NotificationCatalog}; the category
 * decides whether a user may opt out of the email.
 *
 * <p><strong>Opt-out classification.</strong> Only {@link #REMINDER} and {@link #DEVELOPMENT} are
 * opt-out-able: they are advance heads-ups / informational updates whose content is also on the
 * in-app inbox. Everything else is transactional or a security/safety notice and is always sent:
 * <ul>
 *   <li>{@link #BOOKING} — requests, confirmations, declines, expiries, cancellations, reschedules,
 *       no-shows and payment-unresolved notices change a contract the user is party to (and often
 *       their money); suppressing them would hide a state change the user must act on.</li>
 *   <li>{@link #SESSION_PACK} — a pack expiring or being paused changes the user's paid credit.</li>
 *   <li>{@link #MODERATION} — a content-safety notice about the user's own upload.</li>
 *   <li>{@link #ACCOUNT} — activation, OTP, password reset, email verification, profile-change
 *       security notices. Never opt-out-able.</li>
 *   <li>{@link #SYSTEM} — operator alerts / infrastructure probes, never sent to end users.</li>
 * </ul>
 * There are no marketing, announcement or messaging-digest emails in the platform today; add a new
 * opt-out-able category here when one is introduced rather than reusing an existing one.
 */
public enum NotificationCategory {
    BOOKING(false),
    REMINDER(true),
    SESSION_PACK(false),
    DEVELOPMENT(true),
    MODERATION(false),
    ACCOUNT(false),
    SYSTEM(false);

    private final boolean optOutable;

    NotificationCategory(final boolean optOutable) {
        this.optOutable = optOutable;
    }

    public boolean optOutable() {
        return optOutable;
    }
}
