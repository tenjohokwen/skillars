package com.softropic.skillars.platform.security.contract.exception;

/**
 * An email-change confirm or undo link that cannot be used (skillars-deferred-172): unknown, expired, already
 * used, superseded, or refused by a check at use time. One answer for every cause, so the response says
 * nothing about which one, nor about any other account. Mapped to 400 {@code email.change.linkInvalid}.
 */
public class EmailChangeLinkInvalidException extends RuntimeException {

    public static final String ERROR_KEY = "email.change.linkInvalid";

    public EmailChangeLinkInvalidException(final String reason) {
        super(reason);
    }
}
